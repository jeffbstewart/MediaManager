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

// Tier D — config surfaces with embedded media.
// These four pages combine list/grid scaffolding (camera grid, tuner +
// channel tables, data-quality table) with embedded media (snapshot
// images, live previews) and dense action toolbars. The pre-scrub
// already tokenized text contrast across these files.
test.describe('admin Tier D — config surfaces', () => {
  test('/admin/cameras renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/cameras');
    await page.waitForSelector('app-camera-settings .camera-list, app-camera-settings .empty-text');
    await auditA11y(page);
  });

  test('/admin/live-tv renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/live-tv');
    await page.waitForSelector('app-live-tv-settings .ltv-page');
    await auditA11y(page);
  });

  test('/admin/data-quality renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/data-quality');
    await page.waitForSelector('app-data-quality table, app-data-quality .empty-text');
    await auditA11y(page);
  });

  test('/admin/document-ownership renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/document-ownership');
    await page.waitForSelector('app-document-ownership .ownership-page');
    await auditA11y(page);
  });
});

// -------- Interactive states (per-row action menu + edit modal) --------

test.describe('admin Tier D — interactive states', () => {
  test('/admin/data-quality — per-row Actions menu', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await auditOpenState(page, async () => {
      await page.goto('/admin/data-quality');
      await page.waitForSelector('app-data-quality table');
      await page.locator('app-data-quality button[aria-label="Actions"]').first().click();
      await page.waitForSelector('.mat-mdc-menu-panel');
    }, '.mat-mdc-menu-panel');
  });

  test('/admin/data-quality — Edit dialog with TMDB search', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.route('**/api/v2/admin/media-item/search-tmdb*', r =>
      r.fulfill({ json: { results: [
        { tmdb_id: 100, title: 'The Matrix', media_type: 'MOVIE', release_year: 1999, poster_path: '/matrix.jpg', overview: 'A computer hacker.' },
      ] } }));
    await auditOpenState(page, async () => {
      await page.goto('/admin/data-quality');
      await page.waitForSelector('app-data-quality table');
      await page.locator('app-data-quality button[aria-label="Actions"]').first().click();
      await page.locator('.mat-mdc-menu-panel button', { hasText: 'Edit' }).click();
      await page.waitForSelector('app-data-quality .modal-overlay');
      // Press Enter to fire the search instead of clicking the Search
      // button — clicking leaves a Material ripple element on the
      // button that axe occasionally flags for transient contrast.
      const search = page.locator('app-data-quality .modal-overlay input[placeholder="Search TMDB..."]');
      await search.fill('matrix');
      await search.press('Enter');
      await page.waitForSelector('app-data-quality .modal-overlay .tmdb-results');
    }, 'app-data-quality .modal-overlay');
  });
});
