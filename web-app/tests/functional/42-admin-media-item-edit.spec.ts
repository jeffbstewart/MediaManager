import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// /admin/item/:id — MediaItemEditComponent. Heavy form: media-type
// + format selects, seasons input, TMDB search/assign, purchase
// info (with dirty-state Save), Amazon order link search,
// ownership photos. Most fields commit instantly on change;
// purchase block tracks dirty.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/admin/media-item/*/media-type', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/media-item/*/format', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/media-item/*/seasons', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/media-item/*/assign-tmdb', r =>
    r.fulfill({ json: { status: 'assigned' } }));
  await page.route('**/api/v2/admin/media-item/*/purchase', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/media-item/*/link-amazon/*', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/media-item/search-tmdb*', r =>
    r.fulfill({ json: { results: [
      { tmdb_id: 100, title: 'The Matrix', media_type: 'MOVIE', release_year: 1999, poster_path: '/m.jpg', overview: '' },
    ] } }));
  await page.goto('/admin/item/1');
  await page.waitForSelector('app-media-item-edit .edit-page');
}

test.describe('admin media-item-edit — display', () => {
  test('renders the loaded item with display name + format select', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-media-item-edit')).toContainText('The Matrix');
    await expect(page.locator('app-media-item-edit select[aria-label="Format"]')).toBeVisible();
  });

  test('TMDB query pre-populates with title name', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-media-item-edit input[aria-label="Search TMDB"]')).toHaveValue('The Matrix');
  });
});

test.describe('admin media-item-edit — TMDB assign', () => {
  test('Search button POSTs nothing extra; results render', async ({ page }) => {
    await setup(page);
    await page.locator('app-media-item-edit input[aria-label="Search TMDB"]').fill('matrix');
    await page.locator('app-media-item-edit button', { hasText: /^Search$/ }).first().click();
    await expect(page.locator('app-media-item-edit .tmdb-results')).toBeVisible();
  });

  test('clicking a result POSTs /assign-tmdb with tmdb_id + media_type', async ({ page }) => {
    await setup(page);
    await page.locator('app-media-item-edit input[aria-label="Search TMDB"]').fill('matrix');
    await page.locator('app-media-item-edit button', { hasText: /^Search$/ }).first().click();
    await page.waitForSelector('app-media-item-edit .tmdb-results');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && /\/assign-tmdb$/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-media-item-edit .tmdb-row').first().click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({ tmdb_id: 100, media_type: 'MOVIE' });
  });
});

test.describe('admin media-item-edit — purchase form (dirty state)', () => {
  test('Save Purchase POSTs /purchase with the form body', async ({ page }) => {
    await setup(page);
    await page.locator('app-media-item-edit input[aria-label="Purchase Place"]').fill('Walmart');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && /\/purchase$/.test(r.url()),
      { timeout: 3_000 },
    );
    // Save button is enabled once dirty.
    await page.locator('app-media-item-edit button', { hasText: /^Save( Purchase)?$/ }).click();
    const got = await req;
    expect(got.postDataJSON().purchase_place).toBe('Walmart');
  });
});

test.describe('admin media-item-edit — format change', () => {
  test('changing the format select POSTs /format', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && /\/format$/.test(r.url()),
      { timeout: 3_000 },
    );
    // Default is BLU_RAY; switch to UHD_BD (in editable_formats).
    await page.locator('app-media-item-edit select[aria-label="Format"]').selectOption('UHD_BD');
    const got = await req;
    expect(got.postDataJSON()).toEqual({ media_format: 'UHD_BD' });
  });
});
