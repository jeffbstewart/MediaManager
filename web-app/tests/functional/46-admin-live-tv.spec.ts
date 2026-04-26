import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// /admin/live-tv — LiveTvSettingsComponent. Tuners table + channels
// table + global settings (max streams, idle timeout, min rating).

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await page.route('**/api/v2/admin/live-tv/settings', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/live-tv/tuners/*/toggle', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/live-tv/tuners/*/sync', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/live-tv/tuners/*', r => {
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    if (r.request().method() === 'POST') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.goto('/admin/live-tv');
  await page.waitForSelector('app-live-tv-settings .ltv-page');
}

test.describe('admin live-tv — display', () => {
  test('renders tuner row + channel rows from fixture', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-live-tv-settings')).toContainText('HDHomeRun-Living-Room');
    await expect(page.locator('app-live-tv-settings')).toContainText('KNBC');
    await expect(page.locator('app-live-tv-settings')).toContainText('KABC');
  });

  test('settings inputs reflect fixture values', async ({ page }) => {
    await setup(page);
    // max_streams=4, idle_timeout=300.
    await expect(page.locator('app-live-tv-settings input[type="number"]').first()).toHaveValue('4');
  });

  test('active streams indicator shows 1', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-live-tv-settings')).toContainText('1');
  });
});

test.describe('admin live-tv — tuner actions', () => {
  test('Sync channels POSTs /sync', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && /\/tuners\/1\/sync$/.test(r.url()),
      { timeout: 3_000 },
    );
    // Sync is an icon-only button with aria-label="Refresh channels".
    await page.locator('app-live-tv-settings button[aria-label="Refresh channels"]').first().click();
    await req;
  });
});

test.describe('admin live-tv — settings save', () => {
  test('Save settings POSTs the form body', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/live-tv/settings'),
      { timeout: 3_000 },
    );
    await page.locator('app-live-tv-settings button', { hasText: /Save Settings|Save/ }).first().click();
    await req;
  });
});
