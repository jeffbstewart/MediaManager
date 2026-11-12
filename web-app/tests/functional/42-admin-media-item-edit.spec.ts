import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// /admin/item/:id — MediaItemEditComponent. Heavy form: media-type
// + format selects, seasons input, TMDB search/assign, purchase
// info (with dirty-state Save), Amazon order link search,
// ownership photos. Most fields commit instantly on change;
// purchase block tracks dirty.

async function setup(page: Page, opts: { needsTmdbFix?: boolean } = {}) {
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
  if (opts.needsTmdbFix) {
    // The TMDB search section is gated on needsTmdbFix — only renders
    // when primary title's enrichment_status is FAILED / SKIPPED /
    // ABANDONED. Override the detail fixture to surface it.
    await page.route('**/api/v2/admin/media-item/1', r =>
      r.fulfill({ json: {
        media_item_id: 1, display_name: 'The Matrix', upc: '888574293321',
        product_name: 'The Matrix', media_format: 'BLU_RAY',
        editable_formats: ['DVD', 'BLU_RAY', 'UHD_BD'], media_type: 'MOVIE',
        storage_location: null, purchase_place: 'Best Buy',
        purchase_date: '2023-04-12', purchase_price: 14.99,
        amazon_order_id: null, authors: [], book_series: null,
        titles: [{
          join_id: 1, title_id: 100, title_name: 'The Matrix', media_type: 'MOVIE',
          tmdb_id: null, enrichment_status: 'FAILED', poster_url: null, seasons: null,
        }],
        photo_count: 0, photos: [],
      } }));
  }
  await page.goto('/admin/item/1');
  await page.waitForSelector('app-media-item-edit .edit-page');
}

test.describe('admin media-item-edit — display', () => {
  test('renders the loaded item with display name + format select', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-media-item-edit')).toContainText('The Matrix');
    await expect(page.locator('app-media-item-edit select[aria-label="Format"]')).toBeVisible();
  });

  test('TMDB query pre-populates with title name (when needsTmdbFix)', async ({ page }) => {
    await setup(page, { needsTmdbFix: true });
    await expect(page.locator('app-media-item-edit input[aria-label="Search TMDB"]')).toHaveValue('The Matrix');
  });
});

test.describe('admin media-item-edit — TMDB assign', () => {
  test('Search button POSTs nothing extra; results render', async ({ page }) => {
    await setup(page, { needsTmdbFix: true });
    await page.locator('app-media-item-edit input[aria-label="Search TMDB"]').fill('matrix');
    await page.locator('app-media-item-edit button', { hasText: /^Search$/ }).first().click();
    await expect(page.locator('app-media-item-edit .tmdb-results')).toBeVisible();
  });

  test('clicking Select on a result POSTs /assign-tmdb with tmdb_id + media_type', async ({ page }) => {
    await setup(page, { needsTmdbFix: true });
    await page.locator('app-media-item-edit input[aria-label="Search TMDB"]').fill('matrix');
    await page.locator('app-media-item-edit button', { hasText: /^Search$/ }).first().click();
    await page.waitForSelector('app-media-item-edit .tmdb-results');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && /\/assign-tmdb$/.test(r.url()),
      { timeout: 3_000 },
    );
    // Each .tmdb-row has its own Select button — the row itself isn't clickable.
    await page.locator('app-media-item-edit .tmdb-row button', { hasText: 'Select' }).first().click();
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
    // Button label is exactly "Save Purchase Info" — see template.
    await page.locator('app-media-item-edit button.save-btn').click();
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
