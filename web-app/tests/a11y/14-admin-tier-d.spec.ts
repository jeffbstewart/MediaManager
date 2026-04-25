import { test } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { auditA11y } from '../helpers/run-axe';

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
