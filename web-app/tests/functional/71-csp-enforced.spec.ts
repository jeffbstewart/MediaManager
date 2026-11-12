import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// The fixture in helpers/test-fixture.ts serves every page with the
// production Content-Security-Policy (read from the server's
// csp-policy.txt) and fails a test that triggers a violation. These
// tests check that machinery: the header is there, a violation is
// detected, the browser files a report for the policy's endpoint, and a
// test that does not opt out fails. All but the last opt out of the
// failure and read the violations the fixture collected.

const BLOCKED_IMAGE = 'https://image.tmdb.org/t/p/w500/blocked.jpg';

/** What Chrome's Reporting API queues for a CSP violation. */
interface QueuedReport {
  type: string;
  destination: string;
  body: { effectiveDirective?: string; blockedURL?: string; documentURL?: string };
}

async function loadThirdPartyImage(page: import('@playwright/test').Page): Promise<void> {
  await page.evaluate((src: string) => {
    const img = document.createElement('img');
    img.src = src;
    document.body.appendChild(img);
  }, BLOCKED_IMAGE);
}

test.use({ allowCspViolations: true });

test.describe('content security policy in the suite', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
  });

  test('every page is served with the production policy', async ({ page }) => {
    const home = (await page.goto('/'))!;
    const csp = home.headers()['content-security-policy'];
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("img-src 'self' data: blob:");
    expect(csp).toContain('report-uri /csp-report');
    expect(csp).toContain('report-to csp-endpoint');
    expect(home.headers()['reporting-endpoints']).toBe('csp-endpoint="/csp-report"');

    const movies = (await page.goto('/content/movies'))!;
    expect(movies.headers()['content-security-policy']).toBe(csp);
  });

  test('an image from a third-party host is blocked and recorded', async ({ page, cspViolations }) => {
    await page.goto('/');
    await page.waitForSelector('app-home');
    await loadThirdPartyImage(page);

    await expect.poll(() => cspViolations.length).toBe(1);
    expect(cspViolations[0].directive).toBe('img-src');
    expect(cspViolations[0].blocked).toBe(BLOCKED_IMAGE);
  });

  test('the browser files a report for the endpoint the policy names', async ({ page }) => {
    // Chrome delivers CSP reports through the Reporting API when the
    // policy has report-to, batched and off the page's network, so a
    // route cannot see them. The DevTools protocol can: it announces
    // each report the browser queues and where it is bound.
    const cdp = await page.context().newCDPSession(page);
    const queued: QueuedReport[] = [];
    cdp.on('Network.reportingApiReportAdded', e => { queued.push(e.report as QueuedReport); });
    await cdp.send('Network.enable');
    await cdp.send('Network.enableReportingApi', { enable: true });

    await page.goto('/');
    await page.waitForSelector('app-home');
    await loadThirdPartyImage(page);

    await expect.poll(() => queued.filter(r => r.type === 'csp-violation').length).toBe(1);
    const report = queued.find(r => r.type === 'csp-violation')!;
    expect(report.destination).toBe('csp-endpoint');
    expect(report.body.effectiveDirective).toBe('img-src');
    expect(report.body.blockedURL).toBe(BLOCKED_IMAGE);
  });

  test('an image from our origin is not a violation', async ({ page, cspViolations }) => {
    await page.goto('/');
    await page.waitForSelector('app-home');
    const loaded = await page.evaluate(() => new Promise<boolean>(resolve => {
      const img = document.createElement('img');
      img.onload = () => resolve(true);
      img.onerror = () => resolve(false);
      img.src = '/posters/w185/500';
      document.body.appendChild(img);
    }));
    expect(loaded).toBe(true);
    expect(cspViolations).toEqual([]);
  });
});

test.describe('content security policy without the opt-out', () => {
  test.use({ allowCspViolations: false });

  test('a violation fails the test', async ({ page, cspViolations }) => {
    // The fixture throws after this body passes; that failure is the
    // expected one. If this test ever passes, violations are no longer
    // detected and the suite stops guarding the policy.
    test.fail(true, 'the cspViolations fixture fails this test once the body ends');
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await page.waitForSelector('app-home');
    await loadThirdPartyImage(page);
    await expect.poll(() => cspViolations.length).toBe(1);
  });
});
