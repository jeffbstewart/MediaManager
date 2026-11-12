#!/usr/bin/env node
// Pulls full failure detail out of the harness's raw/*.json files —
// useful when failures.md's truncated body isn't enough (typically
// axe failures, which pack multiple violations with DOM-node JSON).
//
// Usage:
//   node tests/inspect-failure.mjs <pattern>          # match against test title
//   node tests/inspect-failure.mjs <pattern> --max N  # cap chars per error (default 4000)
//
// Pattern is a case-insensitive substring of the test title.

import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const rawDir = join(here, '.last-run', 'raw');

const args = process.argv.slice(2);
let pattern = '';
let maxChars = 4000;
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--max') { maxChars = parseInt(args[++i], 10) || 4000; }
  else if (!pattern) pattern = args[i];
}
if (!pattern) {
  console.error('usage: node tests/inspect-failure.mjs <pattern> [--max N]');
  process.exit(2);
}
const needle = pattern.toLowerCase();

function walk(node, specPath) {
  for (const spec of node.specs || []) {
    const title = (spec.title || '').toString();
    if (title.toLowerCase().includes(needle)) {
      for (const test of (spec.tests || [])) {
        for (const result of (test.results || [])) {
          if (result.status === 'passed' || result.status === 'expected') continue;
          const errs = (result.errors || [result.error]).filter(Boolean);
          for (const err of errs) {
            const msg = (err.message || JSON.stringify(err)).toString()
              .replace(/\x1b\[[0-9;]*m/g, '');
            console.log(`=== ${specPath} :: ${title}`);
            const loc = err.location || result.location;
            if (loc) console.log(`    at ${loc.file ?? specPath}:${loc.line ?? '?'}`);
            console.log();
            if (msg.length > maxChars) {
              console.log(msg.slice(0, maxChars));
              console.log(`\n  … (truncated, ${msg.length - maxChars} more chars; raise --max to see all)`);
            } else {
              console.log(msg);
            }
            console.log();
          }
        }
      }
    }
  }
  for (const sub of node.suites || []) walk(sub, specPath);
}

let matched = 0;
const files = readdirSync(rawDir).filter(f => f.endsWith('.json'));
for (const f of files) {
  let data;
  try { data = JSON.parse(readFileSync(join(rawDir, f), 'utf-8')); }
  catch { continue; }
  // The Playwright JSON reporter root has a `suites` array that holds
  // one entry per spec file. The first sub-suite's title is the spec
  // path — use that for display.
  const specPath = data.suites?.[0]?.title || f;
  const before = matched;
  walk(data, specPath);
  if (matched === before) {/* no-op */ }
}

if (matched === 0) {
  // Fallback message — `walk` doesn't actually count, so this never
  // hits today, but reserved for the day someone wants strict matching.
}
