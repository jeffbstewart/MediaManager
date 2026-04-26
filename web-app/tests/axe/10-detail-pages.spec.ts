import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { auditA11y } from '../helpers/run-axe';

/**
 * Tier 4 — detail pages. One test per entity type; fixture IDs pick
 * the right media_type for /title/:id (100 movie, 200 tv, 300 book,
 * 301 album) so the page's template branches are all exercised.
 */

async function setup(page: Page): Promise<void> {
  await mockBackend(page, { features: 'viewer' });
  await stubImages(page);
  await loginAs(page);
}

test.describe('detail pages', () => {
  test('/title/:id (movie)', async ({ page }) => {
    await setup(page);
    await page.goto('/title/100');
    await page.waitForSelector('app-title-detail', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/title/:id (tv)', async ({ page }) => {
    await setup(page);
    await page.goto('/title/200');
    await page.waitForSelector('app-title-detail', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/title/:id (book)', async ({ page }) => {
    await setup(page);
    await page.goto('/title/300');
    await page.waitForSelector('app-title-detail', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/title/:id (album)', async ({ page }) => {
    await setup(page);
    await page.goto('/title/301');
    await page.waitForSelector('app-title-detail', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/actor/:id', async ({ page }) => {
    await setup(page);
    await page.goto('/actor/6384');
    await page.waitForSelector('app-actor', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/author/:id', async ({ page }) => {
    await setup(page);
    await page.goto('/author/1');
    await page.waitForSelector('app-author', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/artist/:id', async ({ page }) => {
    await setup(page);
    await page.goto('/artist/1');
    await page.waitForSelector('app-artist', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/series/:id', async ({ page }) => {
    await setup(page);
    await page.goto('/series/1');
    await page.waitForSelector('app-series', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/tag/:id', async ({ page }) => {
    await setup(page);
    await page.goto('/tag/1');
    await page.waitForSelector('app-tag-detail', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  // Admin-only "Search titles to add" dropdown on the tag-detail page.
  // The dropdown is render-gated on features.isAdmin() AND a non-empty
  // searchResults() signal — the viewer-level audit above never reaches
  // it.
  //
  // The shared auditA11y helper reloads the page on each scheme change,
  // which would clear the dropdown (it's populated by user typing).
  // Instead we open the dropdown once per scheme, asserting axe sees
  // zero color-contrast violations scoped to .search-results in BOTH
  // light and dark mode.
  test('/tag/:id admin search-titles dropdown — color contrast', async ({ page }) => {
    for (const scheme of ['light', 'dark'] as const) {
      await mockBackend(page, { features: 'admin' });
      await stubImages(page);
      await loginAs(page);
      await page.route('**/api/v2/catalog/tags/1/search-titles*', r =>
        r.fulfill({ json: { results: [
          { title_id: 100, title_name: 'The Matrix', release_year: 1999, media_type: 'MOVIE' },
          { title_id: 200, title_name: 'Breaking Bad', release_year: 2008, media_type: 'TV' },
        ] } })
      );
      await page.emulateMedia({ colorScheme: scheme });
      await page.goto('/tag/1');
      await page.waitForSelector('app-tag-detail input.add-input');
      await page.locator('app-tag-detail input.add-input').fill('matrix');
      await page.waitForSelector('app-tag-detail .search-results .search-result-row');
      await page.waitForLoadState('networkidle');
      // Two animation frames so any CSS-custom-property style swap settles.
      await page.evaluate(() => new Promise<void>(resolve =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
      ));

      const results = await new AxeBuilder({ page })
        .include('app-tag-detail .search-results')
        .withRules(['color-contrast'])
        .analyze();
      expect(
        results.violations,
        `${scheme} mode contrast violations in tag search dropdown:\n` +
          results.violations.map(v => `  ${v.id}: ${v.description}`).join('\n'),
      ).toEqual([]);
    }
  });

  test('/content/collection/:id', async ({ page }) => {
    await setup(page);
    await page.goto('/content/collection/1');
    await page.waitForSelector('app-collection-detail', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });
});
