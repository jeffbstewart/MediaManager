import { test, type Page } from '@playwright/test';
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

  test('/content/collection/:id', async ({ page }) => {
    await setup(page);
    await page.goto('/content/collection/1');
    await page.waitForSelector('app-collection-detail', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });
});
