import { test, type Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { auditA11y } from '../helpers/run-axe';

/**
 * Tier 7 — standalone surfaces. These routes live outside the shell:
 * the video player, the ebook reader, and the device-pairing page.
 * Auditing them ensures the full-bleed chrome still meets a11y
 * standards even though it doesn't share the shell's landmarks.
 */

async function setup(page: Page): Promise<void> {
  await mockBackend(page, { features: 'viewer' });
  await stubImages(page);
  await loginAs(page);
}

test.describe('standalone surfaces', () => {
  test('/play/:transcodeId', async ({ page }) => {
    await setup(page);
    await page.goto('/play/1');
    await page.waitForSelector('app-player', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/reader/:mediaItemId', async ({ page }) => {
    await setup(page);
    await page.goto('/reader/300');
    await page.waitForSelector('app-reader', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('/pair?code=ABC123', async ({ page }) => {
    await setup(page);
    await page.goto('/pair?code=ABC123');
    await page.waitForSelector('app-pair-confirm', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });
});
