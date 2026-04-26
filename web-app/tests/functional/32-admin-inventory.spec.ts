import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// /admin/inventory — InventoryReportComponent. Single mat-card with
// a checkbox (include photos) and Generate-PDF / Generate-CSV
// buttons. POSTs /report/csv/start or /report/pdf/start, then polls
// /report/status until status=complete or status=error, then offers
// a Download button.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await page.route('**/api/v2/admin/report/csv/start', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/report/pdf/start*', r => r.fulfill({ status: 204 }));
  await page.goto('/admin/inventory');
  await page.waitForSelector('app-inventory-report mat-card');
}

test.describe('admin inventory — render', () => {
  test('header + photo-count copy + both Generate buttons', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-inventory-report mat-card-title')).toContainText('Insurance Inventory Report');
    // Fixture: 12 photos across 7 items.
    await expect(page.locator('app-inventory-report')).toContainText('12 photos');
    await expect(page.locator('app-inventory-report')).toContainText('7 items');
    await expect(page.locator('app-inventory-report button', { hasText: 'Generate PDF' })).toBeVisible();
    await expect(page.locator('app-inventory-report button', { hasText: 'Generate CSV' })).toBeVisible();
  });

  test('include-photos checkbox is enabled when photos > 0', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-inventory-report .checkbox-row input')).not.toBeDisabled();
  });

  test('include-photos checkbox is disabled when no photos exist', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await page.route('**/api/v2/admin/report/info', r =>
      r.fulfill({ json: { photo_count: 0, items_with_photos: 0 } }));
    await page.goto('/admin/inventory');
    await page.waitForSelector('app-inventory-report mat-card');
    await expect(page.locator('app-inventory-report .checkbox-row input')).toBeDisabled();
  });
});

test.describe('admin inventory — generate flow', () => {
  test('Generate CSV POSTs /report/csv/start + then polls /status', async ({ page }) => {
    await setup(page);
    await page.route('**/api/v2/admin/report/status', r =>
      r.fulfill({ json: { status: 'running', phase: 'Collecting items', current: 0, total: 100, error: null } }));
    const start = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/report/csv/start'),
      { timeout: 3_000 },
    );
    const poll = page.waitForRequest(r =>
      r.method() === 'GET' && r.url().endsWith('/api/v2/admin/report/status'),
      { timeout: 5_000 },
    );
    await page.locator('app-inventory-report button', { hasText: 'Generate CSV' }).click();
    await start;
    await poll;
    // Indeterminate / determinate progress bar shows up.
    await expect(page.locator('app-inventory-report .progress-section')).toBeVisible();
  });

  test('Generate PDF includes photos query param when checkbox is checked', async ({ page }) => {
    await setup(page);
    await page.locator('app-inventory-report .checkbox-row input').check();
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().includes('/api/v2/admin/report/pdf/start'),
      { timeout: 3_000 },
    );
    await page.locator('app-inventory-report button', { hasText: 'Generate PDF' }).click();
    const got = await req;
    expect(got.url()).toMatch(/photos=true/);
  });

  test('completion swaps in the Download button', async ({ page }) => {
    await setup(page);
    let pollCount = 0;
    await page.route('**/api/v2/admin/report/status', r => {
      pollCount++;
      // First poll: running. Second: complete. Keeps the test snappy.
      if (pollCount === 1) {
        return r.fulfill({ json: { status: 'running', phase: 'Working', current: 1, total: 5, error: null } });
      }
      return r.fulfill({ json: { status: 'complete', phase: 'Done', current: 5, total: 5, error: null } });
    });
    await page.locator('app-inventory-report button', { hasText: 'Generate PDF' }).click();
    await expect(page.locator('app-inventory-report button', { hasText: 'Download PDF' })).toBeVisible({ timeout: 4_000 });
  });

  test('error status surfaces the error message', async ({ page }) => {
    await setup(page);
    await page.route('**/api/v2/admin/report/status', r =>
      r.fulfill({ json: { status: 'error', phase: '', current: 0, total: 0, error: 'Disk full' } }));
    await page.locator('app-inventory-report button', { hasText: 'Generate CSV' }).click();
    await expect(page.locator('app-inventory-report .error-text')).toContainText('Disk full', { timeout: 4_000 });
  });
});
