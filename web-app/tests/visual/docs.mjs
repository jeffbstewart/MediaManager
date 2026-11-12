#!/usr/bin/env node
// Write the documentation screenshots (docs/images/screenshots) from the
// mocked app. See docs/SCREENSHOT_PROCEDURES.md.
//
//   node tests/visual/docs.mjs                 # into docs/images/screenshots
//   node tests/visual/docs.mjs --out DIR       # somewhere else, to compare
//
// Needs a dev server on port 4200 and a Chromium Playwright can launch;
// `tests/ci-run.sh docs` provides both. Every picture uses invented
// names (tests/helpers/fictional-names.ts) and generated artwork
// (tests/helpers/image-stub.ts), so nothing in it is anyone else's.
import { spawnSync } from 'node:child_process';
import { mkdirSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};

const webApp = resolve(fileURLToPath(import.meta.url), '..', '..', '..');
const out = resolve(webApp, opt('--out', '../docs/images/screenshots'));
mkdirSync(out, { recursive: true });

const result = spawnSync(
  process.platform === 'win32' ? 'npx.cmd' : 'npx',
  ['playwright', 'test', 'tests/visual/docs.spec.ts', '--reporter=list', '--workers=2'],
  {
    cwd: webApp,
    stdio: 'inherit',
    shell: process.platform === 'win32',
    env: { ...process.env, DOCS_OUT: out, MM_FICTIONAL_NAMES: '1' },
  },
);
if (result.status !== 0) {
  console.error(`docs: playwright exited with ${result.status}`);
  process.exit(result.status ?? 1);
}
const files = readdirSync(out).filter(f => f.endsWith('.png')).sort();
console.log(`docs: ${files.length} screenshots in ${out}`);
for (const f of files) console.log(`  ${f}`);
