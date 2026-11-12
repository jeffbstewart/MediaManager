#!/usr/bin/env node
// Capture review screenshots of the main views (see capture.spec.ts).
//
//   node tests/visual/capture.mjs --out ../data/screenshots/my-branch
//   node tests/visual/capture.mjs --out DIR --themes marquee
//
// Needs a dev server on port 4200 and a Chromium Playwright can launch.
// `tests/ci-run.sh capture --out DIR` provides both (it starts the server
// and points Playwright at the host browser where the pinned one is
// missing); `npx ng serve` by hand works too on a desk machine. Writes
// <out>/<view>-<width>-<theme>.png and prints the list. With ARGOS_UPLOAD set
// (the `visual` job in .github/workflows/ci.yml) the Argos reporter is
// attached instead and the screenshots are uploaded, not written here.
//
// The reporter has to be chosen here: Playwright's --reporter flag
// replaces the config's reporter list wholesale, so an entry in
// playwright.config.ts would never be seen by this run.
import { spawnSync } from 'node:child_process';
import { mkdirSync, readdirSync, renameSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};

const webApp = resolve(fileURLToPath(import.meta.url), '..', '..', '..');
const out = resolve(webApp, opt('--out', '../data/screenshots/visual'));
const themes = opt('--themes', 'marquee');
mkdirSync(out, { recursive: true });

const reporters = ['list', ...(process.env.ARGOS_UPLOAD ? ['@argos-ci/playwright/reporter'] : [])];
const result = spawnSync(
  process.platform === 'win32' ? 'npx.cmd' : 'npx',
  ['playwright', 'test', 'tests/visual/capture.spec.ts', `--reporter=${reporters.join(',')}`, '--workers=2'],
  {
    cwd: webApp,
    stdio: 'inherit',
    shell: process.platform === 'win32',
    env: { ...process.env, VISUAL_OUT: out, VISUAL_THEMES: themes },
  },
);

if (result.status !== 0) {
  console.error(`capture: playwright exited with ${result.status}`);
  process.exit(result.status ?? 1);
}
if (process.env.ARGOS_UPLOAD) {
  // The reporter attached the screenshots to the test results and
  // uploaded them on exit (it prints the build URL, or the error).
  console.log('capture: screenshots uploaded to Argos; nothing written to disk');
  process.exit(0);
}
// The spec captures through argosScreenshot. Without the Argos reporter
// it writes <out>/<project>/<name>.png plus a metadata sidecar per file;
// flatten that to the documented <out>/<view>-<width>-<theme>.png so the
// chain brief's paths stay true.
for (const entry of readdirSync(out, { withFileTypes: true })) {
  if (!entry.isDirectory()) continue;
  const dir = resolve(out, entry.name);
  for (const f of readdirSync(dir)) {
    if (f.endsWith('.png')) renameSync(resolve(dir, f), resolve(out, f));
    else if (f.endsWith('.argos.json')) rmSync(resolve(dir, f));
  }
  rmSync(dir, { recursive: true, force: true });
}
const files = readdirSync(out).filter(f => f.endsWith('.png')).sort();
console.log(`capture: ${files.length} screenshots in ${out}`);
for (const f of files) console.log(`  ${f}`);
