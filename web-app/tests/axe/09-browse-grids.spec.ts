import { test, type Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { auditA11y } from '../helpers/run-axe';

/**
 * Tier 3 — browse grids. Each route fetches a single list response and
 * renders a grid. Every test shares the same scaffold (mock backend,
 * stub images, log in, navigate, audit).
 */

async function setup(page: Page): Promise<void> {
  await mockBackend(page, { features: 'viewer' });
  await stubImages(page);
  await loginAs(page);
}

test.describe('browse grids', () => {
  test('/content/movies', async ({ page }) => {
    await setup(page);
    await page.goto('/content/movies');
    await page.waitForSelector('app-title-grid', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/content/tv', async ({ page }) => {
    await setup(page);
    await page.goto('/content/tv');
    await page.waitForSelector('app-title-grid', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/content/books', async ({ page }) => {
    // Replaced the title-grid in 2026-05 with an author-exploration
    // grid (mirror of /content/music). Drilling into an author lands
    // on the author-detail page which already audits in 10-detail-pages.
    await setup(page);
    await page.goto('/content/books');
    await page.waitForSelector('app-books', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/content/music', async ({ page }) => {
    await setup(page);
    await page.goto('/content/music');
    await page.waitForSelector('app-music', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/content/family', async ({ page }) => {
    await setup(page);
    await page.goto('/content/family');
    await page.waitForSelector('app-personal-videos', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/content/collections', async ({ page }) => {
    await setup(page);
    await page.goto('/content/collections');
    await page.waitForSelector('app-collections', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/content/tags', async ({ page }) => {
    await setup(page);
    await page.goto('/content/tags');
    await page.waitForSelector('app-tags', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/content/playlists', async ({ page }) => {
    await setup(page);
    await page.goto('/content/playlists');
    await page.waitForSelector('app-playlists', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });
});
