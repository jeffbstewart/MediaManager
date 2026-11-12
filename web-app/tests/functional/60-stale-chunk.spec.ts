import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { expectView } from '../helpers/expect-view';

// After a redeploy, a tab still running the previous build asks for a
// lazy route's chunk by its old hashed name. The server answered such a
// miss with index.html (200, text/html), so the browser's import()
// failed with a module error and the route never loaded: the header
// search box appeared to do nothing. The app recovers by reloading once,
// which picks up the new build, and lands on the page that was asked
// for. It never reloads twice for the same failure.

const STALE_HTML = '<!doctype html><html><body>index.html from the new build</body></html>';

/** Script URLs the page has loaded so far; anything else is a lazy chunk. */
async function loadedScripts(page: import('@playwright/test').Page): Promise<Set<string>> {
  return new Set(await page.evaluate(() =>
    performance.getEntriesByType('resource').map(e => e.name).filter(n => n.endsWith('.js'))));
}

test.describe('stale lazy chunk after a redeploy', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await page.waitForSelector('header.app-header');
  });

  test('a header search still reaches the results page', async ({ page }) => {
    const loaded = await loadedScripts(page);
    let staleAnswers = 0;
    await page.route('**/*.js', route => {
      if (loaded.has(route.request().url()) || staleAnswers > 0) return route.continue();
      staleAnswers++;
      return route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: STALE_HTML });
    });

    await page.locator('.search-input').fill('Tom Hanks');
    await page.locator('.search-input').press('Enter');

    await expectView(page, /\/search\?q=Tom(%20|\+)Hanks$/, 'app-search');
    await expect(page.locator('app-search h2')).toContainText('Tom Hanks');
    expect(staleAnswers).toBe(1);
  });

  test('a chunk that keeps failing reloads the page only once', async ({ page }) => {
    const loaded = await loadedScripts(page);
    let loads = 0;
    page.on('load', () => loads++);
    await page.route('**/*.js', route =>
      loaded.has(route.request().url())
        ? route.continue()
        : route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: STALE_HTML }));

    await page.locator('.search-input').fill('Tom Hanks');
    await page.locator('.search-input').press('Enter');

    // One recovery reload, then the failure is allowed to stand.
    await expect.poll(() => loads, { timeout: 5_000 }).toBe(1);
    await page.waitForTimeout(3_000);
    expect(loads).toBe(1);
  });
});
