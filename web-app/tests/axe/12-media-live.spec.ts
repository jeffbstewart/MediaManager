import { test, type Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { auditA11y } from '../helpers/run-axe';

/**
 * Tier 6 — media + live. User-facing playlist detail (regular and
 * smart), the camera grid, the live-tv channel guide, and the
 * full-bleed live-tv player.
 */

async function setup(page: Page): Promise<void> {
  await mockBackend(page, { features: 'viewer' });
  await stubImages(page);
  await loginAs(page);
}

test.describe('media + live', () => {
  test('/playlist/:id', async ({ page }) => {
    await setup(page);
    await page.goto('/playlist/1');
    await page.waitForSelector('app-playlist-detail', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/playlist/smart/:key', async ({ page }) => {
    await setup(page);
    await page.goto('/playlist/smart/recently-played');
    await page.waitForSelector('app-smart-playlist-detail', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/cameras', async ({ page }) => {
    await setup(page);
    await page.goto('/cameras');
    // /cameras's component selector doesn't appear as its own tag in
    // the DOM under the router outlet, so we probe the rendered page
    // states (grid, empty, or error) instead.
    await page.waitForSelector('.camera-grid, .empty-message, .error-message', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/live-tv', async ({ page }) => {
    await setup(page);
    await page.goto('/live-tv');
    await page.waitForSelector('app-live-tv', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/live-tv/:channelId', async ({ page }) => {
    await setup(page);
    await page.goto('/live-tv/1');
    await page.waitForSelector('app-live-tv-player', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });
});
