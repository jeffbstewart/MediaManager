import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// /admin/valuation — ValuationComponent. Summary cards, filter bar
// (search + Unpriced Only chip), paged mat-table, edit dialog with
// Amazon-link + Keepa-search subflows.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  // Subflow stubs
  await page.route('**/api/v2/admin/valuations/*', r => {
    if (r.request().method() === 'POST') return r.fulfill({ json: { ok: true } });
    return r.fallback();
  });
  await page.route('**/api/v2/admin/valuations/*/keepa-search', r =>
    r.fulfill({ json: { results: [
      { asin: 'B00ABC1234', title: 'The Matrix Blu-ray', price_new: 19.99, price_amazon: 17.50 },
    ] } }),
  );
  await page.route('**/api/v2/admin/valuations/*/keepa-apply', r =>
    r.fulfill({ json: { ok: true } }));
  await page.route('**/api/v2/admin/media-item/*/amazon-orders*', r =>
    r.fulfill({ json: { orders: [], search_query: 'matrix' } }));
  await page.route('**/api/v2/admin/media-item/*/link-amazon/*', r =>
    r.fulfill({ json: { ok: true } }));
  await page.goto('/admin/valuation');
  await page.waitForSelector('app-valuation table');
}

test.describe('admin valuation — summary + display', () => {
  test('summary cards reflect fixture totals', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-valuation .summary-card').nth(0)).toContainText('3');
    await expect(page.locator('app-valuation .summary-card').nth(1)).toContainText('$39.98');
    await expect(page.locator('app-valuation .summary-card').nth(2)).toContainText('$49.98');
  });

  test('renders all 3 fixture rows with name + UPC + price', async ({ page }) => {
    await setup(page);
    const rows = page.locator('app-valuation tbody tr');
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0).locator('.item-name')).toContainText('The Matrix');
    await expect(rows.nth(0).locator('.upc-label')).toContainText('888574293321');
    await expect(rows.nth(0)).toContainText('$14.99');
    // Ratatouille has null prices → "—".
    await expect(rows.nth(2)).toContainText('Ratatouille');
    await expect(rows.nth(2)).toContainText('—');
  });

  test('photo icon renders only on rows with photos', async ({ page }) => {
    await setup(page);
    // Matrix=2 photos, Inception=0, Ratatouille=1.
    await expect(page.locator('app-valuation tbody tr').nth(0).locator('.photo-icon')).toBeVisible();
    await expect(page.locator('app-valuation tbody tr').nth(1).locator('.photo-icon')).toHaveCount(0);
    await expect(page.locator('app-valuation tbody tr').nth(2).locator('.photo-icon')).toBeVisible();
  });
});

test.describe('admin valuation — filter bar', () => {
  test('typing in search fires GET with search=...', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/valuations') && /search=matrix/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-valuation .search-input').fill('matrix');
    await req;
  });

  test('Unpriced Only chip toggles + adds unpriced_only=true', async ({ page }) => {
    await setup(page);
    const chip = page.locator('app-valuation mat-chip', { hasText: 'Unpriced Only' });
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/valuations') && /unpriced_only=true/.test(r.url()),
      { timeout: 3_000 },
    );
    await chip.click();
    await req;
    await expect(chip).toHaveClass(/mat-mdc-chip-highlighted/);
  });
});

test.describe('admin valuation — Edit dialog', () => {
  test('Edit pre-fills place / date / price / replacement / asin', async ({ page }) => {
    await setup(page);
    await page.locator('app-valuation tr', { hasText: 'Inception' })
      .locator('button[aria-label="Edit valuation"]').click();
    const dialog = page.locator('app-valuation .modal-overlay');
    await expect(dialog.locator('h3')).toContainText('Inception');
    const inputs = dialog.locator('input.field-input');
    await expect(inputs.nth(0)).toHaveValue('Amazon');
    await expect(inputs.nth(1)).toHaveValue('2024-01-05');
    await expect(inputs.nth(2)).toHaveValue('24.99');
    await expect(inputs.nth(3)).toHaveValue('29.99');
  });

  test('save POSTs to /api/v2/admin/valuations/:id with the form body', async ({ page }) => {
    await setup(page);
    await page.locator('app-valuation tr', { hasText: 'Inception' })
      .locator('button[aria-label="Edit valuation"]').click();
    const dialog = page.locator('app-valuation .modal-overlay');
    await dialog.locator('input.field-input').nth(0).fill('Costco');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/valuations/2'),
      { timeout: 3_000 },
    );
    // The Save button label may vary; rely on the primary mat-flat-button
    // inside the dialog footer.
    await dialog.locator('button', { hasText: /^(Save|Update|Apply)$/ }).first().click();
    const got = await req;
    expect(got.postDataJSON().purchase_place).toBe('Costco');
  });
});
