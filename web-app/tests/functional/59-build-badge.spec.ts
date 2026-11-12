import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Build badge: the running build's label (the merged PR number on a
// release image) is shown under the title of the help page, in normal
// flow, with no animation. It comes from `build-info.json` beside
// index.html, fetched uncached; when that file is missing the badge is
// simply absent. No other page shows it.

const badge = (page: import('@playwright/test').Page) => page.locator('app-build-badge .build-badge');

test.describe('build badge', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
  });

  test('shows the build label under the help page title', async ({ page }) => {
    await page.route('**/build-info.json', r => r.fulfill({ json: { label: 'PR #4242' } }));
    await page.goto('/help');
    const b = badge(page);
    await expect(b).toHaveText('PR #4242');
    await expect(page.locator('app-build-badge .build-line')).toHaveText('Build PR #4242');
    const titleBox = (await page.locator('app-help h1').boundingBox())!;
    const badgeBox = (await b.boundingBox())!;
    const tocBox = (await page.locator('app-help nav.toc').boundingBox())!;
    expect(badgeBox.y).toBeGreaterThanOrEqual(titleBox.y + titleBox.height);
    expect(badgeBox.y + badgeBox.height).toBeLessThanOrEqual(tocBox.y);
  });

  test('stays put: no fade, still shown well after the page loads', async ({ page }) => {
    await page.route('**/build-info.json', r => r.fulfill({ json: { label: 'PR #4242' } }));
    await page.goto('/help');
    const b = badge(page);
    await expect(b).toBeVisible();
    await expect(b).toHaveCSS('animation-name', 'none');
    await page.waitForTimeout(5_000);
    await expect(b).toBeVisible();
    await expect(b).toHaveCSS('opacity', '1');
  });

  test('reads the label fresh on every load rather than from the browser cache', async ({ page }) => {
    // The first response invites caching; a cached re-read on the second
    // load would keep showing the old label after a redeploy.
    let label = 'PR #1';
    await page.route('**/build-info.json', r => r.fulfill({
      json: { label },
      headers: { 'Cache-Control': 'public, max-age=3600' },
    }));
    await page.goto('/help');
    await expect(badge(page)).toHaveText('PR #1');
    label = 'PR #2';
    await page.goto('/help');
    await expect(badge(page)).toHaveText('PR #2');
  });

  test('renders nothing when build-info.json is unavailable', async ({ page }) => {
    await page.route('**/build-info.json', r => r.fulfill({ status: 404, body: 'not found' }));
    await page.goto('/help');
    await page.waitForSelector('app-help h1');
    await expect(page.locator('app-build-badge')).toHaveCount(1);
    await expect(badge(page)).toHaveCount(0);
  });

  test('is absent on other pages inside the shell', async ({ page }) => {
    await page.route('**/build-info.json', r => r.fulfill({ json: { label: 'PR #4242' } }));
    await page.goto('/');
    await page.waitForSelector('header.app-header');
    await expect(page.locator('app-build-badge')).toHaveCount(0);
  });
});

test.describe('build badge outside the shell', () => {
  test('is absent on the login page, which renders outside the shell', async ({ page }) => {
    await mockBackend(page, { discover: 'normal' });
    await page.route('**/build-info.json', r => r.fulfill({ json: { label: 'PR #4242' } }));
    await page.goto('/login');
    await page.waitForSelector('app-login form');
    await expect(page.locator('app-build-badge')).toHaveCount(0);
  });
});
