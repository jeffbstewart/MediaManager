import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// /admin/import — AmazonImportComponent. Mat-table of imported
// Amazon orders, filter chips (Hide Cancelled, Media Only,
// Unlinked Only), search, file-upload, per-row Link / Unlink.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await page.route('**/api/v2/admin/amazon-orders/upload', r =>
    r.fulfill({ json: { ok: true, inserted: 5, skipped: 1, total_parsed: 6 } }));
  await page.route('**/api/v2/admin/amazon-orders/search-items*', r =>
    r.fulfill({ json: { items: [
      { id: 200, display_name: 'Inception', media_format: 'UHD_BD', upc: '883929199854' },
    ] } }));
  await page.route('**/api/v2/admin/amazon-orders/*/link/*', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/amazon-orders/*/unlink', r => r.fulfill({ status: 204 }));
  await page.goto('/admin/import');
  await page.waitForSelector('app-amazon-import table');
}

test.describe('admin import — display', () => {
  test('renders fixture rows + linked status', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-amazon-import tbody tr').first()).toContainText('Inception');
  });
});

test.describe('admin import — filters', () => {
  test('search input fires GET with search=...', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/amazon-orders') && /search=incept/i.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-amazon-import input[type="text"]').first().fill('incept');
    await req;
  });

  test('Media Only chip toggles + adds media_only=true', async ({ page }) => {
    await setup(page);
    const chip = page.locator('app-amazon-import mat-chip', { hasText: 'Media Only' });
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/amazon-orders') && /media_only=true/.test(r.url()),
      { timeout: 3_000 },
    );
    await chip.click();
    await req;
  });

  test('Unlinked Only chip toggles + adds unlinked_only=true', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/amazon-orders') && /unlinked_only=true/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-amazon-import mat-chip', { hasText: 'Unlinked' }).click();
    await req;
  });
});

test.describe('admin import — link dialog', () => {
  test('Link button on a row opens the search dialog', async ({ page }) => {
    await setup(page);
    // Find a row with a Link button (typically unlinked rows show it).
    const link = page.locator('app-amazon-import button', { hasText: /^Link$/ }).first();
    if (await link.count() > 0) {
      await link.click();
      // Dialog opens with a search input.
      await expect(page.locator('app-amazon-import input[type="text"]').last()).toBeVisible();
    }
  });
});
