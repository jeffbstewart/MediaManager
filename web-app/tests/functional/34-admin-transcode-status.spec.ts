import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// /admin/transcodes/status — TranscodeStatusComponent. Polls
// /api/v2/admin/transcode-status every 15 s, renders overall stats,
// active buddies table + recent leases table, plus Scan NAS / Clear
// Failures action buttons.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await page.route('**/api/v2/admin/transcode-status/scan', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/transcode-status/clear-failures', r => r.fulfill({ status: 204 }));
  await page.goto('/admin/transcodes/status');
  await page.waitForSelector('app-transcode-status .status-card');
}

test.describe('admin transcode-status — display', () => {
  test('renders the completed-count + bytes header', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-transcode-status .stat-row.highlight')).toContainText('4,321 completed');
    await expect(page.locator('app-transcode-status .stat-row.highlight')).toContainText('12.4 TB processed');
  });

  test('Pending row breaks down by lease type', async ({ page }) => {
    await setup(page);
    const pending = page.locator('app-transcode-status .stat-row', { hasText: 'Pending' }).first();
    await expect(pending).toContainText('12 transcodes');
    await expect(pending).toContainText('3 mobile');
    await expect(pending).toContainText('7 thumbs');
  });

  test('failed-count badge renders when failures > 0', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-transcode-status .failed')).toContainText('1 failed');
  });

  test('Active buddy row renders the in-flight file', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-transcode-status')).toContainText('Buddy-Alpha');
    await expect(page.locator('app-transcode-status')).toContainText('movie.mkv');
  });

  test('Recent leases table renders both completed + failed rows', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-transcode-status table tbody tr')).toHaveCount(2);
    await expect(page.locator('app-transcode-status')).toContainText('show.s01e01.mkv');
    await expect(page.locator('app-transcode-status')).toContainText('broken.mkv');
  });
});

test.describe('admin transcode-status — actions', () => {
  test('Scan NAS POSTs /scan + flips button to Scanning...', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/transcode-status/scan'),
      { timeout: 3_000 },
    );
    await page.locator('app-transcode-status button', { hasText: 'Scan NAS' }).click();
    await req;
    await expect(page.locator('app-transcode-status button', { hasText: 'Scanning...' })).toBeVisible();
  });

  test('Clear Failures button only shows when failed_count > 0', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-transcode-status button', { hasText: 'Clear Failures' })).toBeVisible();
  });

  test('Clear Failures POSTs /clear-failures', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/transcode-status/clear-failures'),
      { timeout: 3_000 },
    );
    await page.locator('app-transcode-status button', { hasText: 'Clear Failures' }).click();
    await req;
  });
});
