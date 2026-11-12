// Custom Playwright test factory. All spec files import { test, expect,
// ... } from this file instead of '@playwright/test' directly. The base
// test is extended with auto-running fixtures:
//
//   - themeFixture seeds the theme the SPA reads before bootstrap (the
//     harness sets MM_THEME for each axe pass).
//   - cspViolations serves every page with the production
//     Content-Security-Policy and fails the test on a violation.
//   - autoCoverageFixture collects V8 JS coverage per test and feeds it
//     to monocart-reporter for aggregation across the whole run (and
//     across the harness's per-spec processes via monocart-coverage-
//     reports' shared cache). Source maps in our Angular dev build are
//     inline, so the reporter resolves V8 byte ranges back to
//     TypeScript files automatically.

import { test as base } from '@playwright/test';
import { addCoverageReport } from 'monocart-reporter';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

// MM_THEME (set by the harness for each axe pass) seeds the key the SPA
// reads before bootstrap, so every page in the run renders in that theme.
// The mocked profile reports the same theme as its default (see
// mock-backend.ts), so ThemeService keeps it after the profile loads.
const MM_THEME = process.env['MM_THEME'];

// The production Content-Security-Policy, read from the file the server
// builds its header from (CspDecorator), so the suite enforces exactly
// what the NAS enforces. ng serve sends no such header on its own, which
// is how an image URL on a third-party host reached production without
// a test noticing. Playwright runs from web-app/, so the path is relative
// to the process cwd (see load-fixture.ts for why not import.meta).
const CSP_POLICY_FILE = join(process.cwd(), '..', 'src', 'main', 'resources', 'csp-policy.txt');
const cspDirectives = readFileSync(CSP_POLICY_FILE, 'utf8')
  .split(/\r?\n/)
  .map(line => line.trim())
  .filter(line => line.length > 0 && !line.startsWith('#'));
const cspPolicy = cspDirectives.join('; ');

function cspDirectiveValue(name: string): string {
  const directive = cspDirectives.find(d => d.startsWith(name + ' '));
  if (!directive) throw new Error(`${CSP_POLICY_FILE} has no '${name}' directive`);
  return directive.slice(name.length + 1).trim();
}

// Reporting-Endpoints binds the group the policy's report-to names to the
// URL its report-uri names, the same way CspDecorator derives it, so
// Chrome's Reporting API has somewhere to file the report.
const reportingEndpoints = `${cspDirectiveValue('report-to')}="${cspDirectiveValue('report-uri')}"`;

/** One securitypolicyviolation event, as the page reported it. */
export interface CspViolation {
  /** The directive that blocked the load, such as `img-src`. */
  directive: string;
  /** The URL the policy refused. */
  blocked: string;
  /** The page that tried to load it. */
  document: string;
  /** The script that asked for it, when the browser knows. */
  source: string;
  line: number;
}

export const test = base.extend<{
  autoCoverageFixture: string;
  themeFixture: string;
  /**
   * `test.use({ allowCspViolations: true })` for a test that triggers a
   * violation on purpose; it can then read `cspViolations` instead of
   * failing.
   */
  allowCspViolations: boolean;
  /** Every violation the page reported during the test, oldest first. */
  cspViolations: CspViolation[];
}>({
  themeFixture: [async ({ page }, use) => {
    if (MM_THEME) {
      await page.addInitScript((t: string) => {
        try { localStorage.setItem('mm.theme.effective', t); } catch { /* storage blocked */ }
      }, MM_THEME);
    }
    await use('themeFixture');
  }, { scope: 'test', auto: true }],
  allowCspViolations: [false, { option: true }],
  cspViolations: [async ({ page, allowCspViolations }, use, testInfo) => {
    const violations: CspViolation[] = [];

    // The page hands each violation to the test process as it happens.
    // A binding survives full page loads (sign-out reloads the app), so
    // nothing is lost between documents.
    await page.exposeBinding('__mmCspViolation', (_source, violation: CspViolation) => {
      violations.push(violation);
    });
    await page.addInitScript(() => {
      document.addEventListener('securitypolicyviolation', (e: SecurityPolicyViolationEvent) => {
        (window as unknown as { __mmCspViolation: (v: CspViolation) => void }).__mmCspViolation({
          directive: e.effectiveDirective || e.violatedDirective,
          blocked: e.blockedURI,
          document: e.documentURI,
          source: e.sourceFile,
          line: e.lineNumber,
        });
      }, true);
    });

    // Add the production headers to each document the dev server sends.
    // Documents are the extension-less URLs; everything else (scripts,
    // images, the mocked API) falls through to the other handlers.
    await page.route(url => !/\.[A-Za-z0-9]+$/.test(url.pathname), async route => {
      if (route.request().resourceType() !== 'document') return route.fallback();
      try {
        const response = await route.fetch();
        await route.fulfill({
          response,
          headers: {
            ...response.headers(),
            'content-security-policy': cspPolicy,
            'reporting-endpoints': reportingEndpoints,
          },
        });
      } catch (e) {
        // The page closed while a navigation was in flight (a test ends
        // right after sign-out's reload, say). Nothing is listening for
        // the response; fail the request rather than hide a real error.
        await route.abort().catch(() => undefined);
      }
    });

    await use(violations);

    // Let a navigation the test left in flight finish without a route
    // callback failing after the page is gone.
    await page.unrouteAll({ behavior: 'ignoreErrors' });

    // Report only when the test body passed, so a real failure keeps its
    // own message; the violations are still the first thing to fix. A
    // test marked test.fail() passes its body too, and this is the
    // failure it expects.
    if (!allowCspViolations && violations.length > 0 && testInfo.status === 'passed') {
      const lines = violations.map(v =>
        `${v.directive} blocked ${v.blocked} (${v.source || v.document}${v.line ? ':' + v.line : ''})`);
      throw new Error(
        `The page violated the Content-Security-Policy ${violations.length === 1 ? 'once' : violations.length + ' times'}. ` +
        'Serve the resource from our origin (the image proxy, for example), or opt this test out with ' +
        `test.use({ allowCspViolations: true }) if the violation is the point:\n  ${lines.join('\n  ')}`,
      );
    }
  }, { scope: 'test', auto: true }],
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
