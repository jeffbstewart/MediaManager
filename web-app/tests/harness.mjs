#!/usr/bin/env node
// Test harness runner for the web-app Playwright suite.
//
// Why this exists: when an LLM (or a CI step) runs the suite the
// long way — bash loop, raw output streamed to stdout — it has to
// re-run failing specs just to find out *what* failed. This harness
// runs each spec once, captures Playwright's JSON reporter output,
// and writes three artifacts under tests/.last-run/:
//
//   summary.txt   — terse human summary, one line per failed test.
//                   This is the "first read" file when an LLM wants
//                   to know if anything is broken.
//   failures.md   — markdown with trimmed per-failure context (error
//                   message, location, screenshot path). Read this
//                   to drill into a specific failure.
//   raw/          — per-spec Playwright JSON output, for tooling.
//
// Usage:
//   node tests/harness.mjs                       # both suites
//   node tests/harness.mjs axe                   # only tests/axe
//   node tests/harness.mjs functional            # only tests/functional
//   node tests/harness.mjs --concurrency 1       # serial (default 4)
//   node tests/harness.mjs --files <a> <b>       # specific files only
//
// Exit code: 0 if all tests pass, 1 if any fail, 2 on usage error.
//
// Parallelism: by default the harness keeps up to 4 spec subprocesses
// in flight at once. Each subprocess is its own isolated Playwright
// invocation (preserves the Windows worker-loader workaround) but
// they run concurrently against the single shared ng-serve dev
// server. Lower the concurrency to 1 for serial debugging.
//
// Progress: a one-line progress bar with elapsed / ETA is rendered to
// stderr while the run is underway. The same progress data is mirrored
// to tests/.last-run/progress.json after every spec completes — that
// file is what an LLM should read to check status, NOT the live stderr
// stream. Reading the file is O(1) tokens and is updated atomically.

import { spawn } from 'node:child_process';
import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync, renameSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..');
const outDir = join(repoRoot, 'tests', '.last-run');
const rawDir = join(outDir, 'raw');

// ---------------- arg parsing ----------------

const args = process.argv.slice(2);
let mode = 'all';
let onlyFiles = null;
let concurrency = 4;
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  if (a === 'axe' || a === 'functional' || a === 'all') {
    mode = a;
  } else if (a === '--concurrency') {
    const n = parseInt(args[++i], 10);
    if (!Number.isFinite(n) || n < 1) {
      process.stderr.write(`invalid --concurrency value\n`);
      process.exit(2);
    }
    concurrency = n;
  } else if (a === '--files') {
    onlyFiles = args.slice(i + 1);
    break;
  } else {
    process.stderr.write(`unknown arg: ${a}\n`);
    process.exit(2);
  }
}

// ---------------- collect specs ----------------

const suites = mode === 'axe' ? ['axe']
              : mode === 'functional' ? ['functional']
              : ['axe', 'functional'];

const specs = [];
if (onlyFiles) {
  for (const f of onlyFiles) specs.push({ suite: pathSuite(f), path: f });
} else {
  for (const suite of suites) {
    const dir = join(repoRoot, 'tests', suite);
    const files = readdirSync(dir).filter(f => f.endsWith('.spec.ts')).sort();
    for (const f of files) specs.push({ suite, path: `tests/${suite}/${f}` });
  }
}

function pathSuite(p) {
  if (p.includes('tests/axe/')) return 'axe';
  if (p.includes('tests/functional/')) return 'functional';
  return 'unknown';
}

// ---------------- reset output dir ----------------

rmSync(outDir, { recursive: true, force: true });
mkdirSync(rawDir, { recursive: true });

// ---------------- run + collect ----------------

const startedAt = new Date();
const startedAtMs = Date.now();
// perSpec is index-aligned with the specs array so the failures
// section comes out in deterministic order even though specs finish
// in whatever order parallel workers complete them.
const perSpec = new Array(specs.length).fill(null);
let totalPass = 0, totalFail = 0, totalSkip = 0;
let completedCount = 0;
const active = new Map(); // spec.path -> startTime  (specs currently in flight)
const isTty = process.stderr.isTTY;
const barWidth = 40;

writeProgress(); // initial 0% line

// Worker-pool execution: keep up to `concurrency` subprocesses in
// flight; as each finishes we start the next from the queue. Each
// subprocess is fully isolated (own JSON output file, own coverage
// dir) so they don't interfere despite sharing the dev server.
let nextIndex = 0;
await new Promise((resolveAll) => {
  const startNext = () => {
    while (active.size < concurrency && nextIndex < specs.length) {
      const idx = nextIndex++;
      runSpec(idx, () => {
        if (nextIndex < specs.length) {
          startNext();
        } else if (active.size === 0) {
          resolveAll();
        }
      });
    }
  };
  startNext();
});

function runSpec(idx, onDone) {
  const spec = specs[idx];
  const t0 = Date.now();
  active.set(spec.path, t0);
  // Don't pass --reporter=json on the CLI — that would override the
  // config's reporter array and silence monocart-reporter (no
  // coverage collection). Instead, rely on the config's `json`
  // reporter and tell it where to write via PLAYWRIGHT_JSON_FILE.
  const safeName = spec.path.replace(/[\\/]/g, '_');
  const jsonOut = join(rawDir, `${safeName}.json`);
  // Per-spec coverage output dir — read by playwright.config.ts so
  // each subprocess writes its raw V8 entries somewhere unique.
  // The harness's final merge step ingests every per-spec dir.
  const covDir = join(outDir, 'coverage-raw', safeName);
  const proc = spawn('npx', ['playwright', 'test', spec.path], {
    cwd: repoRoot,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: {
      ...process.env,
      PLAYWRIGHT_JSON_FILE: jsonOut,
      MCR_OUTPUT_DIR: covDir,
    },
    shell: process.platform === 'win32',
  });
  // Buffer stdout/stderr in memory so we can write them after exit.
  // Volume is small per-spec (Playwright list-reporter output ≤ a few
  // KB); no streaming to disk required.
  const stdoutChunks = [];
  const stderrChunks = [];
  proc.stdout.on('data', d => stdoutChunks.push(d));
  proc.stderr.on('data', d => stderrChunks.push(d));
  proc.on('close', (exitStatus) => {
    const dt = Date.now() - t0;
    const stdout = Buffer.concat(stdoutChunks).toString('utf-8');
    const stderr = Buffer.concat(stderrChunks).toString('utf-8');
    if (stdout.trim()) writeFileSync(join(rawDir, `${safeName}.stdout`), stdout);
    if (stderr.trim()) writeFileSync(join(rawDir, `${safeName}.stderr`), stderr);

    let parsed = null;
    try { parsed = JSON.parse(readFileSync(jsonOut, 'utf-8')); } catch { /* leave null */ }

    const r = collectResults(parsed, spec, stderr, exitStatus);
    r.durationMs = dt;
    perSpec[idx] = r;
    totalPass += r.passed;
    totalFail += r.failed;
    totalSkip += r.skipped;
    completedCount++;
    active.delete(spec.path);

    renderProgress();
    writeProgress();
    onDone();
  });
  // Render a fresh progress line as soon as a new worker picks up
  // a spec — gives the user visual confirmation that parallelism is
  // happening.
  renderProgress();
}

// Newline after the final progress line so the post-run summary
// lands cleanly.
if (isTty) process.stderr.write('\n');

// ---------------- write artifacts ----------------

writeFileSync(join(outDir, 'summary.txt'), formatSummary());
writeFileSync(join(outDir, 'failures.md'), formatFailures());
writeFileSync(join(outDir, 'run.json'), JSON.stringify({
  startedAt: startedAt.toISOString(),
  finishedAt: new Date().toISOString(),
  totalPass, totalFail, totalSkip,
  specs: perSpec,
}, null, 2));

// Coverage merge — each per-spec subprocess wrote raw V8 entries to
// tests/.last-run/coverage-raw/<spec>/raw/. The shared cache pattern
// races under parallel workers, so we point each at its own dir and
// run a final merge here using the standalone monocart-coverage-
// reports package (transitive dep of monocart-reporter).
const coverageRawRoot = join(outDir, 'coverage-raw');
const finalCoverageDir = join(outDir, 'coverage');
try {
  const inputDirs = readdirSync(coverageRawRoot)
    .map(name => join(coverageRawRoot, name, 'raw'))
    .filter(p => {
      try { return readdirSync(p).length > 0; } catch { return false; }
    });
  if (inputDirs.length > 0) {
    const { CoverageReport } = await import('monocart-coverage-reports');
    const report = new CoverageReport({
      name: 'mediamanager web-app',
      inputDir: inputDirs,
      outputDir: finalCoverageDir,
      entryFilter: { '**/node_modules/**': false, '**/*': true },
      sourceFilter: { '**/node_modules/**': false, '**/src/app/**': true },
      reports: [['v8'], ['json-summary']],
      logging: 'off',
    });
    await report.generate();
    const cov = JSON.parse(readFileSync(join(finalCoverageDir, 'coverage-summary.json'), 'utf-8'));
    writeFileSync(join(outDir, 'coverage.txt'), formatCoverage(cov));
  }
} catch (e) {
  process.stderr.write(`coverage merge failed: ${e.message}\n`);
}

// Final summary line — designed so anyone (CI tail, terminal user,
// LLM glancing at the last line) gets the verdict at a glance:
//   ✓ axe 17/17, functional 26/26 — 357 passed, 0 failed (2m51s)
//   ✘ axe 17/17, functional 25/26 — 1 FAILED in tests/functional/04-...:103
const elapsed = fmtTime(Date.now() - startedAtMs);
const bySuiteCounts = new Map();
for (const r of perSpec) {
  const s = bySuiteCounts.get(r.suite) || { passSpecs: 0, totalSpecs: 0 };
  s.totalSpecs++;
  if (r.failed === 0) s.passSpecs++;
  bySuiteCounts.set(r.suite, s);
}
const suiteStr = Array.from(bySuiteCounts).map(([s, c]) => `${s} ${c.passSpecs}/${c.totalSpecs}`).join(', ');
let mark, tail;
if (totalFail === 0) {
  mark = '✓';
  tail = `${totalPass} passed, 0 failed (${elapsed})`;
} else {
  mark = '✘';
  const firstFail = perSpec.flatMap(r => r.failures.map(f => ({ path: r.path, line: f.line }))).at(0);
  const where = firstFail ? `first: ${firstFail.path}:${firstFail.line}` : '';
  tail = `${totalFail} FAILED, ${totalPass} passed (${elapsed}) — ${where}`;
}
process.stderr.write(`\n${mark} ${suiteStr} — ${tail}\n`);
process.stderr.write(`  details: ${relative(repoRoot, outDir)}/{summary.txt,failures.md}\n`);
process.exit(totalFail > 0 ? 1 : 0);

// ---------------- helpers ----------------

/**
 * Render a single-line progress bar to stderr. Uses CR + clear-EOL
 * when on a TTY so the line updates in place; falls back to one
 * line per update when piped (CI logs).
 *
 * Reads the live `active` map and `completedCount` counter rather
 * than taking arguments — every caller wants the current snapshot.
 * With concurrency > 1 the line includes "[N active]" plus the
 * shortest active spec name so the user sees parallelism happening.
 */
function renderProgress() {
  const total = specs.length;
  const done = completedCount;
  const elapsedMs = Date.now() - startedAtMs;
  // ETA estimate uses the per-spec average TIMES the remaining spec
  // count divided by concurrency — accounting for parallel throughput.
  const avgPerSpecMs = done > 0 ? elapsedMs / done : 0;
  const remainingSpecs = total - done;
  const remainingMs = avgPerSpecMs > 0
    ? (avgPerSpecMs * remainingSpecs) / Math.max(1, Math.min(concurrency, remainingSpecs))
    : 0;
  const filled = Math.round(barWidth * (done / total));
  const bar = '█'.repeat(filled) + '·'.repeat(barWidth - filled);
  const pct = ((done / total) * 100).toFixed(0).padStart(3);
  const failBadge = totalFail > 0 ? `  \x1b[31m${totalFail} FAILED\x1b[0m` : '';
  let status = '';
  if (active.size > 0) {
    // Pick the most recently started active spec for display — gives
    // a sense of forward progress even when others are still running.
    const recent = Array.from(active.entries())
      .sort((a, b) => b[1] - a[1])[0]?.[0];
    const prefix = active.size > 1 ? `  [${active.size} active] → ` : `  → `;
    status = prefix + shortPath(recent);
  } else if (done === total) {
    status = '  done';
  }
  const line = `[${bar}] ${pct}%  ${done}/${total}  ${fmtTime(elapsedMs)} elapsed  ETA ${fmtTime(remainingMs)}${failBadge}${status}`;
  if (isTty) {
    process.stderr.write('\r\x1b[2K' + line);
  } else {
    process.stderr.write(line + '\n');
  }
}

function fmtTime(ms) {
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}m${r.toString().padStart(2, '0')}s`;
}

function shortPath(p) {
  // Drop the "tests/" prefix to keep the line under 100 cols.
  return p.replace(/^tests\//, '');
}

/**
 * Atomically write tests/.last-run/progress.json. Anyone reading the
 * file while we're updating it will see either the old or new state,
 * never a half-written file. Read this file to check status without
 * touching the harness's stderr.
 */
function writeProgress() {
  const elapsedMs = Date.now() - startedAtMs;
  const done = completedCount;
  const total = specs.length;
  const avgPerSpecMs = done > 0 ? elapsedMs / done : 0;
  const remainingSpecs = total - done;
  const remainingMs = avgPerSpecMs > 0
    ? (avgPerSpecMs * remainingSpecs) / Math.max(1, Math.min(concurrency, remainingSpecs))
    : 0;
  const data = {
    startedAt: startedAt.toISOString(),
    updatedAt: new Date().toISOString(),
    elapsedSec: Math.round(elapsedMs / 1000),
    etaSec: Math.round(remainingMs / 1000),
    completedSpecs: done,
    totalSpecs: total,
    percent: total > 0 ? Math.round((done / total) * 100) : 0,
    concurrency,
    totals: { passed: totalPass, failed: totalFail, skipped: totalSkip },
    activeSpecs: Array.from(active.keys()),
    completed: perSpec.filter(Boolean).map(r => ({
      path: r.path,
      passed: r.passed,
      failed: r.failed,
      skipped: r.skipped,
      durationMs: r.durationMs,
    })),
    done: done === total,
  };
  // Write to a tmp then rename for atomicity.
  const dst = join(outDir, 'progress.json');
  const tmp = dst + '.tmp';
  writeFileSync(tmp, JSON.stringify(data, null, 2));
  renameSync(tmp, dst);
}

/** Walk the Playwright JSON reporter shape and pull failure detail. */
function collectResults(parsed, spec, stderr, exitStatus) {
  const out = {
    suite: spec.suite,
    path: spec.path,
    passed: 0, failed: 0, skipped: 0,
    durationMs: 0,
    failures: [],
  };
  if (!parsed || !parsed.suites) {
    // Couldn't parse JSON — count as 1 failure with the stderr blurb
    // so the user sees something actionable.
    out.failed = 1;
    out.failures.push({
      title: '<spec did not produce JSON>',
      file: spec.path, line: 0,
      error: (stderr || `exit ${exitStatus}`).split('\n').slice(0, 8).join('\n'),
      screenshot: null,
    });
    return out;
  }
  walkSuite(parsed, out);
  return out;
}

function walkSuite(node, out) {
  if (Array.isArray(node.specs)) {
    for (const s of node.specs) walkSpec(s, out);
  }
  if (Array.isArray(node.suites)) {
    for (const sub of node.suites) walkSuite(sub, out);
  }
}

function walkSpec(spec, out) {
  for (const t of (spec.tests || [])) {
    // status: 'expected' | 'unexpected' | 'skipped' | 'flaky'
    if (t.status === 'skipped') { out.skipped++; continue; }
    if (t.status === 'expected') { out.passed++; continue; }
    // 'unexpected' or 'flaky' → treat as failure.
    out.failed++;
    const lastResult = (t.results || []).at(-1) || {};
    const err = (lastResult.errors || [lastResult.error]).filter(Boolean)[0] || {};
    const errMsg = (err.message || JSON.stringify(err)).toString();
    // Strip ANSI escape sequences and trim.
    const clean = errMsg.replace(/\x1b\[[0-9;]*m/g, '').trim();
    // First non-empty line is the headline; up to ~10 lines of body.
    const lines = clean.split('\n').filter(l => l.length > 0);
    const headline = lines[0] || '<no message>';
    const body = lines.slice(0, 10).join('\n');
    const loc = err.location || lastResult.location || {};
    const screenshot = (lastResult.attachments || [])
      .find(a => /screenshot/i.test(a.name) || /png$/i.test(a.contentType || ''))
      ?.path || null;
    out.failures.push({
      title: spec.title,
      file: loc.file || spec.file || out.path,
      line: loc.line || spec.line || 0,
      headline,
      body,
      screenshot,
    });
  }
}

function formatSummary() {
  const when = startedAt.toISOString().replace('T', ' ').slice(0, 19);
  const lines = [];
  // First line is the at-a-glance verdict — same shape as the final
  // stderr line, so the very first thing read (whether it's the user
  // glancing or an LLM doing `head -1 summary.txt`) is enough to know
  // whether to dig deeper.
  const elapsedSec = perSpec.reduce((a, r) => a + r.durationMs, 0) / 1000;
  if (totalFail === 0) {
    lines.push(`OK — ${totalPass} passed, 0 failed across ${perSpec.length} specs (${elapsedSec.toFixed(0)}s)`);
  } else {
    const first = perSpec.flatMap(r => r.failures.map(f => ({ path: r.path, line: f.line }))).at(0);
    const where = first ? ` — first: ${first.path}:${first.line}` : '';
    lines.push(`FAIL — ${totalFail} failed, ${totalPass} passed across ${perSpec.length} specs (${elapsedSec.toFixed(0)}s)${where}`);
  }
  lines.push('');
  lines.push(`Test harness — ${when} UTC`);
  lines.push(`Mode: ${mode}`);
  lines.push('');
  // Per-suite roll-up.
  const bySuite = new Map();
  for (const r of perSpec) {
    const s = bySuite.get(r.suite) || { pass: 0, fail: 0, skip: 0, dur: 0, files: 0 };
    s.pass += r.passed; s.fail += r.failed; s.skip += r.skipped;
    s.dur += r.durationMs; s.files++;
    bySuite.set(r.suite, s);
  }
  for (const [suite, s] of bySuite) {
    const tot = s.pass + s.fail;
    const mark = s.fail === 0 ? 'OK' : `${s.fail} FAILED`;
    lines.push(`${suite.padEnd(11)} ${s.pass}/${tot} passed across ${s.files} specs (${(s.dur / 1000).toFixed(1)}s) — ${mark}`);
  }
  lines.push('');
  lines.push(`TOTAL: ${totalPass} passed, ${totalFail} failed, ${totalSkip} skipped`);
  lines.push('');
  if (totalFail > 0) {
    lines.push('FAILURES:');
    for (const r of perSpec) {
      for (const f of r.failures) {
        lines.push(`  ${r.path}:${f.line}`);
        lines.push(`    › ${f.title}`);
        lines.push(`    ${f.headline}`);
      }
    }
    lines.push('');
    lines.push('Drill-down: tests/.last-run/failures.md');
  } else {
    lines.push('All tests passed.');
  }
  return lines.join('\n') + '\n';
}

function formatFailures() {
  if (totalFail === 0) return '# No failures.\n';
  const lines = [];
  lines.push(`# Test failures — ${startedAt.toISOString()}`);
  lines.push('');
  for (const r of perSpec) {
    for (const f of r.failures) {
      lines.push(`## ${r.path}:${f.line} — ${f.title}`);
      lines.push('');
      lines.push('```');
      lines.push(f.body || f.headline || '<no error body>');
      lines.push('```');
      if (f.screenshot) {
        lines.push('');
        lines.push(`Screenshot: \`${f.screenshot}\``);
      }
      lines.push('');
    }
  }
  return lines.join('\n');
}

/**
 * Render the monocart coverage-summary.json into a digestible text
 * report. Designed for at-a-glance triage:
 *   - First line: overall % across lines / statements / branches
 *   - Top-level "Files with 0% coverage" callout (untouched components)
 *   - Top 20 lowest-covered files (excluding 0% — those have their own
 *     section) so partial gaps surface
 *
 * Skips the `localhost-4200/chunk-*.js` synthetic entries Angular's
 * dev server emits — those aren't actionable source files.
 */
function formatCoverage(cov) {
  const total = cov.total;
  const files = Object.entries(cov)
    .filter(([k]) => k !== 'total' && k.startsWith('src/'))
    .map(([path, stats]) => ({ path, ...stats }));

  const zero = files.filter(f => f.lines.pct === 0)
    .sort((a, b) => a.path.localeCompare(b.path));
  const partial = files.filter(f => f.lines.pct > 0 && f.lines.pct < 100)
    .sort((a, b) => a.lines.pct - b.lines.pct);

  const lines = [];
  lines.push(
    `Coverage: lines ${total.lines.pct.toFixed(1)}%  ` +
    `statements ${total.statements.pct.toFixed(1)}%  ` +
    `branches ${total.branches.pct.toFixed(1)}%  ` +
    `functions ${total.functions.pct.toFixed(1)}%  ` +
    `(${files.length} app source files)`
  );
  lines.push('');
  if (zero.length > 0) {
    lines.push(`Files with 0% line coverage (${zero.length}):`);
    for (const f of zero.slice(0, 50)) {
      lines.push(`  ${f.path}  (${f.lines.total} lines)`);
    }
    if (zero.length > 50) lines.push(`  … +${zero.length - 50} more`);
    lines.push('');
  } else {
    lines.push('No files with 0% line coverage.');
    lines.push('');
  }
  lines.push(`Lowest-covered partial files (top 20 of ${partial.length}):`);
  for (const f of partial.slice(0, 20)) {
    const pct = f.lines.pct.toFixed(1).padStart(5);
    lines.push(`  ${pct}%  ${f.path}  (${f.lines.covered}/${f.lines.total} lines)`);
  }
  lines.push('');
  lines.push('HTML report: tests/.last-run/coverage/index.html');
  return lines.join('\n') + '\n';
}
