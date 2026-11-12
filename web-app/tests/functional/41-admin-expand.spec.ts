import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// /admin/expand — ExpandComponent. Mat-table of multi-pack items
// awaiting expansion. Per-row Expand opens a modal where the admin
// adds titles via TMDB search, then marks expanded or "not a
// multi-pack".

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/admin/expand/*/add-title', r =>
    r.fulfill({ json: { ok: true, title_id: 999, title_name: 'New Title', disc_number: 3 } }));
  await page.route('**/api/v2/admin/expand/*/title/*', r => {
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.route('**/api/v2/admin/expand/*/mark-expanded', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/expand/*/not-multipack', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/expand/search-tmdb*', r =>
    r.fulfill({ json: { results: [
      { tmdb_id: 100, title: 'The Matrix', media_type: 'MOVIE', release_year: 1999, poster_path: '/m.jpg' },
    ] } }));
  await page.goto('/admin/expand');
  await page.waitForSelector('app-expand table');
}

test.describe('admin expand — display', () => {
  test('renders 1 fixture row with product name + Expand button', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-expand tbody tr')).toHaveCount(1);
    // The row shows product_name + media_format + title-count badge +
    // Expand button. linked_titles render only inside the dialog,
    // so we don't assert their presence here.
    await expect(page.locator('app-expand .product-name')).toContainText('Lord of the Rings Trilogy');
    await expect(page.locator('app-expand tbody tr button', { hasText: 'Expand' })).toBeVisible();
  });
});

test.describe('admin expand — Expand dialog', () => {
  test('opens with linked titles, search input + Mark Expanded button', async ({ page }) => {
    await setup(page);
    await page.locator('app-expand button', { hasText: 'Expand' }).first().click();
    const dialog = page.locator('app-expand .modal-overlay');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('The Fellowship of the Ring');
    await expect(dialog.locator('input[placeholder="Search TMDB..."]')).toBeVisible();
    await expect(dialog.locator('button', { hasText: 'Mark' }).or(dialog.locator('button', { hasText: 'Done' })))
      .toBeVisible();
  });

  test('TMDB search fires GET /expand/search-tmdb with q+type', async ({ page }) => {
    await setup(page);
    await page.locator('app-expand button', { hasText: 'Expand' }).first().click();
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/expand/search-tmdb') && /q=matrix/i.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-expand .modal-overlay input[placeholder="Search TMDB..."]').fill('matrix');
    await req;
  });

  test('Mark Expanded POSTs /mark-expanded', async ({ page }) => {
    await setup(page);
    await page.locator('app-expand button', { hasText: 'Expand' }).first().click();
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && /\/mark-expanded$/.test(r.url()),
      { timeout: 3_000 },
    );
    // The button text says "Mark Expanded" or similar; match flexibly.
    await page.locator('app-expand .modal-overlay button', { hasText: /Mark.*[Ee]xpanded/ }).click();
    await req;
  });

  test('Not a Multi-Pack POSTs /not-multipack', async ({ page }) => {
    await setup(page);
    await page.locator('app-expand button', { hasText: 'Expand' }).first().click();
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && /\/not-multipack$/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-expand .modal-overlay button', { hasText: /Not a Multi-Pack/i }).click();
    await req;
  });
});
