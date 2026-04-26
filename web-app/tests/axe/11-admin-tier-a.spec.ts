import { test } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { auditA11y } from '../helpers/run-axe';

// Tier A — admin nav + simple list/detail pages.
// All admin routes inherit the shell, so the page-level chrome is the
// same as user-facing tiers; what's new here is the admin payload that
// renders inside `<main>`. The pre-scrub commit (0e5735a) tokenized the
// dim-text patterns shared across these pages, so most violations should
// already be gone.
test.describe('admin Tier A — list pages', () => {
  test('/admin/users renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/users');
    await page.waitForSelector('app-users table, app-users .empty-text');
    await auditA11y(page);
  });

  test('/admin/tags renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/tags');
    await page.waitForSelector('app-tag-management table, app-tag-management .empty-text');
    await auditA11y(page);
  });

  test('/admin/family-members renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/family-members');
    await page.waitForSelector('app-family-members table, app-family-members .empty-text');
    await auditA11y(page);
  });

  test('/admin/purchase-wishes renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/purchase-wishes');
    await page.waitForSelector('app-purchase-wishes table, app-purchase-wishes .empty-text');
    await auditA11y(page);
  });

  test('/admin/valuation renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/valuation');
    await page.waitForSelector('app-valuation table');
    await auditA11y(page);
  });

  test('/admin/reports renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/reports');
    await page.waitForSelector('app-reports table, app-reports .empty-message');
    await auditA11y(page);
  });

  test('/admin/inventory renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/inventory');
    await page.waitForSelector('app-inventory-report mat-card');
    await auditA11y(page);
  });
});
