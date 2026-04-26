import { test, type Page } from '@playwright/test';
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
    await page.waitForSelector('app-wishlist', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/discover', async ({ page }) => {
    await setup(page);
    await page.goto('/discover');
    await page.waitForSelector('app-discover', { state: 'attached' });
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
