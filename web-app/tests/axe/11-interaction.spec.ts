import { test, type Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { auditA11y } from '../helpers/run-axe';

/**
 * Tier 5 — interaction-driven pages. Search (with a query and the
 * Advanced dialog variant), wishlist, discover, profile, help.
 */

async function setup(page: Page): Promise<void> {
  await mockBackend(page, { features: 'viewer' });
  await stubImages(page);
  await loginAs(page);
}

test.describe('interaction pages', () => {
  test('/search?q=matrix', async ({ page }) => {
    await setup(page);
    await page.goto('/search?q=matrix');
    await page.waitForSelector('app-search', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/search (no query: page search box and empty state)', async ({ page }) => {
    await setup(page);
    await page.goto('/search');
    await page.waitForSelector('app-search .search-box', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/search?advanced=1 (opens Advanced dialog)', async ({ page }) => {
    await setup(page);
    await page.goto('/search?advanced=1');
    await page.waitForSelector('app-advanced-search-dialog', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/wishlist', async ({ page }) => {
    await setup(page);
    await page.goto('/wishlist');
    await page.waitForSelector('app-wishlist .media-grid app-poster-card', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/discover', async ({ page }) => {
    await setup(page);
    await page.goto('/discover');
    await page.waitForSelector('app-discover', { state: 'attached' });
    // The frame (hero, promo, New Seasons Out) comes from the home feed.
    await page.waitForSelector('app-discover .hero-section app-hero-banner');
    await page.waitForSelector('app-discover .new-seasons app-wide-card');
    // The rows from the feed and ListTitles. The artist cards are not
    // awaited: on a direct load the music-radio flag can arrive after
    // the page mounts, which shows the Last.fm explainer instead.
    await page.waitForSelector('app-discover .author-books app-poster-card');
    await page.waitForSelector('app-discover .popular app-poster-card');
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/discover (artist recommendations)', async ({ page }) => {
    await setup(page);
    // In-app navigation, so the music-radio flag is loaded before the
    // page mounts and the artist person cards render.
    await page.goto('/');
    await page.locator('aside.side-nav a[href="/discover"]').click();
    await page.waitForSelector('app-discover .artists-section app-person-card');
    await page.waitForSelector('app-discover .popular app-poster-card');
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/profile', async ({ page }) => {
    await setup(page);
    await page.goto('/profile');
    await page.waitForSelector('app-profile', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/help', async ({ page }) => {
    await setup(page);
    await page.goto('/help');
    await page.waitForSelector('app-help', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });
});
