import { test } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { auditA11y } from '../helpers/run-axe';

// Tier C — heavy form pages.
// These exercise mat-form-field at scale plus custom inputs (file
// upload, color swatch, sticky save bar, dirty-state warning). Most
// per-field contrast issues should already be covered by the global
// `mat.form-field-overrides()` mixin in styles.scss.
test.describe('admin Tier C — heavy forms', () => {
  test('/admin/add renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/add');
    await page.waitForSelector('app-add-item .add-page');
    await auditA11y(page);
  });

  test('/admin/expand renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/expand');
    await page.waitForSelector('app-expand table, app-expand .empty-state');
    await auditA11y(page);
  });

  test('/admin/item/:id renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/item/1');
    await page.waitForSelector('app-media-item-edit .edit-page');
    await auditA11y(page);
  });

  test('/admin/import renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/import');
    await page.waitForSelector('app-amazon-import table, app-amazon-import .empty-text');
    await auditA11y(page);
  });

  test('/admin/settings renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/settings');
    await page.waitForSelector('app-settings .settings-page');
    await auditA11y(page);
  });
});
