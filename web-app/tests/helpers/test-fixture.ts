// Custom Playwright test factory that auto-collects V8 JS coverage
// per test and feeds it to monocart-reporter for aggregation across
// the whole run (and across the harness's per-spec processes via
// monocart-coverage-reports' shared cache).
//
// All spec files import { test, expect, ... } from this file instead
// of '@playwright/test' directly. The base test is extended with an
// auto-running fixture that:
//   1. Starts page.coverage.startJSCoverage before each test (chromium)
//   2. Stops it after the test, hands the V8 entries to addCoverageReport
//   3. monocart-reporter writes raw coverage to the configured outputDir
//      with clean=false so per-spec processes accumulate into one report.
//
// Source maps in our Angular dev build are inline, so the reporter
// resolves V8 byte ranges back to TypeScript files automatically.

import { test as base } from '@playwright/test';
import { addCoverageReport } from 'monocart-reporter';

export const test = base.extend<{ autoCoverageFixture: string }>({
  autoCoverageFixture: [async ({ page, browserName }, use, testInfo) => {
    const isChromium = browserName === 'chromium';
    if (isChromium) {
      await page.coverage.startJSCoverage({ resetOnNavigation: false });
    }
    await use('autoCoverageFixture');
    if (isChromium) {
      try {
        const jsCoverage = await page.coverage.stopJSCoverage();
        if (jsCoverage.length > 0) await addCoverageReport(jsCoverage, testInfo);
      } catch {
        // Page may already be closed (test failed mid-flight); skip.
      }
    }
  }, { scope: 'test', auto: true }],
});

// Re-export the rest of @playwright/test so spec files only need a
// single import from here.
export { expect, type Page, type BrowserContext, type Route, type Locator, type Request, type Response } from '@playwright/test';
