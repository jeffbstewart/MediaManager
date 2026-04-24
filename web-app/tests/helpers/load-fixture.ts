import { readFileSync } from 'node:fs';
import { join } from 'node:path';

// Playwright runs from web-app/, so fixture paths resolve relative to
// the process cwd. We intentionally avoid `import.meta.url` / __dirname
// tricks — Playwright's TypeScript loader transpiles tests to CJS and
// the ESM hooks for import.meta aren't available.
const fixturesRoot = join(process.cwd(), 'tests', 'fixtures');

/**
 * Synchronously read a JSON fixture under tests/fixtures/. Paths are
 * relative to the fixtures root (e.g. `auth/discover.normal.json`).
 * Parsed every call — keeps tests hermetic, stays fast because files
 * are small.
 */
export function loadFixture<T = unknown>(relativePath: string): T {
  const abs = join(fixturesRoot, relativePath);
  return JSON.parse(readFileSync(abs, 'utf8')) as T;
}
