import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// /admin/add — AddItemComponent. Three tabs: Scan Barcode (UPC input
// + camera), Search TMDB (search + add-from-tmdb), Search Books (OL
// search + add-from-isbn). Plus a Recent Items table at the bottom
// with filter chips and per-row delete.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/admin/add-item/scan', r =>
    r.fulfill({ json: { status: 'created', upc: '888574293321', title_name: 'The Matrix' } }));
  await page.route('**/api/v2/admin/add-item/add-from-tmdb', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/add-item/add-from-isbn', r =>
    r.fulfill({ json: { ok: true, title_name: 'Some Book' } }));
  await page.route('**/api/v2/admin/add-item/search-tmdb*', r =>
    r.fulfill({ json: { results: [
      { tmdb_id: 603, title: 'The Matrix', media_type: 'MOVIE', release_year: 1999, poster_path: '/m.jpg', overview: '' },
    ] } }));
  await page.route('**/api/v2/admin/unmatched-books/search-ol*', r =>
    r.fulfill({ json: { results: [] } }));
  await page.route('**/api/v2/admin/add-item/scan/*', r => {
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.route('**/api/v2/admin/add-item/item/*', r => {
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.goto('/admin/add');
  await page.waitForSelector('app-add-item .add-page');
}

test.describe('admin add-item — Scan Barcode tab', () => {
  test('quota label reflects fixture (12 / 100)', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-add-item .quota-label')).toContainText('12 / 100');
  });

  test('Lookup button disabled when input empty, enables after typing', async ({ page }) => {
    await setup(page);
    const lookup = page.locator('app-add-item button', { hasText: 'Lookup' });
    await expect(lookup).toBeDisabled();
    await page.locator('app-add-item input.upc-input').fill('888574293321');
    await expect(lookup).toBeEnabled();
  });

  test('Lookup POSTs /scan with the UPC body', async ({ page }) => {
    await setup(page);
    await page.locator('app-add-item input.upc-input').fill('888574293321');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/add-item/scan'),
      { timeout: 3_000 },
    );
    await page.locator('app-add-item button', { hasText: 'Lookup' }).click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({ upc: '888574293321' });
  });

  test('successful scan shows the success banner', async ({ page }) => {
    await setup(page);
    await page.locator('app-add-item input.upc-input').fill('888574293321');
    await page.locator('app-add-item button', { hasText: 'Lookup' }).click();
    await expect(page.locator('app-add-item .message-banner.success')).toContainText('Scanned');
  });
});

test.describe('admin add-item — Search TMDB tab', () => {
  test('typing fires GET /search-tmdb', async ({ page }) => {
    await setup(page);
    await page.locator('app-add-item div[role="tab"]', { hasText: 'Search TMDB' }).click();
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/add-item/search-tmdb') && /q=matrix/i.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-add-item input[placeholder="Search TMDB..."]').fill('matrix');
    await req;
  });

  test('result Add button POSTs /add-from-tmdb with format', async ({ page }) => {
    await setup(page);
    await page.locator('app-add-item div[role="tab"]', { hasText: 'Search TMDB' }).click();
    await page.locator('app-add-item input[placeholder="Search TMDB..."]').fill('matrix');
    await page.waitForSelector('app-add-item .tmdb-results');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/add-item/add-from-tmdb'),
      { timeout: 3_000 },
    );
    await page.locator('app-add-item .tmdb-results button', { hasText: /^Add/ }).first().click();
    const got = await req;
    expect(got.postDataJSON().tmdb_id).toBe(603);
    expect(got.postDataJSON().format).toBe('BLURAY');
  });
});

test.describe('admin add-item — Recent Items table', () => {
  test('renders fixture rows', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-add-item table tbody tr').first()).toContainText('The Matrix');
  });

  test('filter chip change re-fetches with the chosen filter', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/add-item/recent') && /filter=NEEDS_ATTENTION/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-add-item mat-chip', { hasText: 'Needs Attention' }).click();
    await req;
  });
});
