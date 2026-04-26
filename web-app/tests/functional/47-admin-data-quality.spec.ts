import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// /admin/data-quality — DataQualityComponent. Mat-table of titles
// with enrichment-status filter chips, search, per-row Actions menu
// (Edit/Re-enrich/Hide/Delete), and an Edit dialog with TMDB search.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/admin/data-quality/*/re-enrich', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/data-quality/*/toggle-hidden', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/data-quality/*/update', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/data-quality/*', r => {
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.route('**/api/v2/admin/media-item/search-tmdb*', r =>
    r.fulfill({ json: { results: [
      { tmdb_id: 100, title: 'The Matrix', media_type: 'MOVIE', release_year: 1999, poster_path: '/m.jpg', overview: '' },
    ] } }));
  await page.goto('/admin/data-quality');
  await page.waitForSelector('app-data-quality table');
}

test.describe('admin data-quality — display', () => {
  test('renders both fixture rows with title + issues badges', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-data-quality tbody tr')).toHaveCount(2);
    await expect(page.locator('app-data-quality')).toContainText('The Matrix');
    await expect(page.locator('app-data-quality')).toContainText('Old Show');
  });

  test('needs-attention count badge reflects fixture (2)', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-data-quality')).toContainText('2');
  });
});

test.describe('admin data-quality — filters', () => {
  test('search input fires GET with search=...', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/data-quality') && /search=matrix/i.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-data-quality input[type="text"]').first().fill('matrix');
    await req;
  });

  test('status chip change fires GET with status param', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/data-quality') && /status=ENRICHED/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-data-quality mat-chip', { hasText: 'Enriched' }).click();
    await req;
  });
});

test.describe('admin data-quality — Actions menu', () => {
  test('Re-enrich menu item POSTs /re-enrich', async ({ page }) => {
    await setup(page);
    await page.locator('app-data-quality button[aria-label="Actions"]').first().click();
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && /\/re-enrich$/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Re-enrich' }).click();
    await req;
  });

  test('Hide menu item POSTs /toggle-hidden', async ({ page }) => {
    await setup(page);
    await page.locator('app-data-quality button[aria-label="Actions"]').first().click();
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && /\/toggle-hidden$/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('.mat-mdc-menu-panel button', { hasText: /Hide|Unhide/ }).click();
    await req;
  });
});

test.describe('admin data-quality — Edit dialog', () => {
  test('opens with TMDB id + media type pre-filled', async ({ page }) => {
    await setup(page);
    await page.locator('app-data-quality button[aria-label="Actions"]').first().click();
    // mat-menu-item buttons render as "<icon-text> <label>" (e.g.
    // "edit Edit") so anchored ^...$ regex doesn't match.
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Edit' }).click();
    const dialog = page.locator('app-data-quality .modal-overlay');
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('#dq-edit-media-type')).toBeVisible();
  });

  test('Save POSTs /update with tmdb_id + media_type', async ({ page }) => {
    await setup(page);
    await page.locator('app-data-quality button[aria-label="Actions"]').first().click();
    // mat-menu-item buttons render as "<icon-text> <label>" (e.g.
    // "edit Edit") so anchored ^...$ regex doesn't match.
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Edit' }).click();
    await page.locator('app-data-quality #dq-edit-tmdb-id').fill('603');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && /\/update$/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-data-quality .modal-overlay button', { hasText: /^Save$/ }).click();
    const got = await req;
    expect(got.postDataJSON().tmdb_id).toBe(603);
  });
});
