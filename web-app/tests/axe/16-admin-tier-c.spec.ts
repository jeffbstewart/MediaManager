import { test, expect, type Page } from '../helpers/test-fixture';
import AxeBuilder from '@axe-core/playwright';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { auditA11y } from '../helpers/run-axe';

/** See 14-admin-tier-a.spec.ts for the rationale on this helper. */
async function auditOpenState(
  page: Page,
  prepare: () => Promise<void>,
  scope: string,
): Promise<void> {
  for (const scheme of ['light', 'dark'] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    await prepare();
    await page.evaluate(() => new Promise<void>(resolve =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
    ));
    const results = await new AxeBuilder({ page })
      .include(scope)
      .exclude('mat-icon')
      .analyze();
    expect(
      results.violations,
      `${scheme} mode violations in ${scope}:\n` +
        results.violations.map(v => `  ${v.id}: ${v.description}`).join('\n'),
    ).toEqual([]);
  }
}

// Shape matches both the add-item TmdbResult and the media-item-edit
// TmdbResult interfaces — tmdb_id (not id), explicit media_type,
// release_year as number, optional overview.
const TMDB_RESULTS = {
  results: [
    { tmdb_id: 100, title: 'The Matrix',   media_type: 'MOVIE', release_year: 1999, poster_path: '/matrix.jpg', overview: 'A computer hacker learns the truth.' },
    { tmdb_id: 200, title: 'Breaking Bad', media_type: 'TV',    release_year: 2008, poster_path: '/bb.jpg',     overview: 'A chemistry teacher diagnosed with cancer.' },
  ],
};

// Tier C — heavy form pages.
// These exercise mat-form-field at scale plus custom inputs (file
// upload, color swatch, sticky save bar, dirty-state warning). Most
// per-field contrast issues should already be covered by the global
// `mat.form-field-overrides()` mixin in styles.scss.
test.describe('admin Tier C — heavy forms', () => {
  test('/admin/add renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/add');
    await page.waitForSelector('app-add-item .add-page');
    await auditA11y(page);
  });

  test('/admin/expand renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/expand');
    await page.waitForSelector('app-expand table, app-expand .empty-state');
    await auditA11y(page);
  });

  test('/admin/item/:id renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/item/1');
    await page.waitForSelector('app-media-item-edit .edit-page');
    await auditA11y(page);
  });

  test('/admin/import renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/import');
    await page.waitForSelector('app-amazon-import table, app-amazon-import .empty-text');
    await auditA11y(page);
  });

  test('/admin/settings renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/settings');
    await page.waitForSelector('app-settings .settings-page');
    await auditA11y(page);
  });
});

// -------- Interactive states (search dropdowns + modals) --------

test.describe('admin Tier C — interactive states', () => {
  test('/admin/add — TMDB search results panel', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.route('**/api/v2/admin/add-item/search-tmdb*', r =>
      r.fulfill({ json: TMDB_RESULTS }));
    await auditOpenState(page, async () => {
      await page.goto('/admin/add');
      await page.waitForSelector('app-add-item .add-page');
      // Search input lives on the "Search TMDB" tab — second tab in
      // the mat-tab-group; default tab is the barcode scanner. Click
      // the tab to swap to the search panel before typing.
      await page.locator('app-add-item div[role="tab"]', { hasText: 'Search TMDB' }).click();
      await page.locator('app-add-item input[placeholder="Search TMDB..."]').fill('matrix');
      await page.waitForSelector('app-add-item .tmdb-results');
    }, 'app-add-item .tmdb-results');
  });

  test('/admin/item/:id — TMDB search results panel', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.route('**/api/v2/admin/media-item/search-tmdb*', r =>
      r.fulfill({ json: TMDB_RESULTS }));
    // The TMDB search section is gated on needsTmdbFix — only renders
    // when the loaded media item's primary title has enrichment_status
    // FAILED / SKIPPED / ABANDONED. Override the detail fixture
    // (which defaults to COMPLETE) to flip the gate. Shape mirrors
    // tests/fixtures/admin/media-item-detail.json so the rest of the
    // component renders normally.
    await page.route('**/api/v2/admin/media-item/1', r =>
      r.fulfill({ json: {
        media_item_id: 1, display_name: 'Mystery Disc', upc: '123',
        product_name: 'Mystery Disc', media_format: 'BLU_RAY',
        editable_formats: ['DVD', 'BLU_RAY', 'UHD_BD'], media_type: 'MOVIE',
        storage_location: null, purchase_place: null, purchase_date: null,
        purchase_price: null, amazon_order_id: null, authors: [], book_series: null,
        titles: [{
          join_id: 1, title_id: 100, title_name: 'Mystery Disc', media_type: 'MOVIE',
          tmdb_id: null, enrichment_status: 'FAILED', poster_url: null, seasons: null,
        }],
        photo_count: 0, photos: [],
      } }));
    await auditOpenState(page, async () => {
      await page.goto('/admin/item/1');
      await page.waitForSelector('app-media-item-edit .edit-page');
      await page.locator('app-media-item-edit input[aria-label="Search TMDB"]').fill('matrix');
      await page.locator('app-media-item-edit button', { hasText: /^Search$/ }).first().click();
      await page.waitForSelector('app-media-item-edit .tmdb-results');
    }, 'app-media-item-edit .tmdb-results');
  });

  test('/admin/expand — Expand modal with TMDB search', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.route('**/api/v2/admin/expand/search-tmdb*', r =>
      r.fulfill({ json: TMDB_RESULTS }));
    await auditOpenState(page, async () => {
      await page.goto('/admin/expand');
      await page.waitForSelector('app-expand table');
      // Click the row's Expand button → opens the modal with the TMDB
      // search input inside.
      await page.locator('app-expand button', { hasText: 'Expand' }).first().click();
      await page.waitForSelector('app-expand .modal-overlay');
      await page.locator('app-expand .modal-overlay input.field-input').fill('matrix');
      await page.waitForSelector('app-expand .modal-overlay .tmdb-results');
    }, 'app-expand .modal-overlay');
  });
});
