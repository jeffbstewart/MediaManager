import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { auditA11y } from '../helpers/run-axe';

/**
 * Audit a transient interactive surface (dialog, menu, dropdown) at a
 * specific selector after caller-supplied steps put the page into the
 * target state. Repeats across both color schemes — `prepare` runs
 * once per scheme so the open state is freshly constructed against the
 * resolved CSS-custom-property values.
 *
 * Why not auditA11y(): the shared helper reloads the page on each
 * scheme switch (CSS color-scheme cache workaround), which would close
 * any open dialog/menu. This keeps state across the per-scheme loop.
 */
async function auditOpenState(
  page: Page,
  prepare: () => Promise<void>,
  scope: string,
): Promise<void> {
  for (const scheme of ['light', 'dark'] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    await prepare();
    await page.evaluate(() => new Promise<void>(resolve =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
    ));
    const results = await new AxeBuilder({ page })
      .include(scope)
      .exclude('mat-icon')
      .analyze();
    expect(
      results.violations,
      `${scheme} mode violations in ${scope}:\n` +
        results.violations.map(v => `  ${v.id}: ${v.description}`).join('\n'),
    ).toEqual([]);
  }
}

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

// -------- Interactive states (dialogs + menus) --------
//
// Route-load axe sweeps above don't see the dialog/menu DOM (it's
// gated on user interaction). These tests open each surface and run
// axe scoped to the open element across both color schemes.

test.describe('admin Tier A — interactive states', () => {
  test('/admin/users — Add User dialog', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await auditOpenState(page, async () => {
      await page.goto('/admin/users');
      await page.waitForSelector('app-users table');
      await page.locator('app-users button', { hasText: 'Add User' }).click();
      await page.waitForSelector('app-users .modal-overlay');
    }, 'app-users .modal-overlay');
  });

  test('/admin/users — per-row Actions menu', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await auditOpenState(page, async () => {
      await page.goto('/admin/users');
      await page.waitForSelector('app-users table');
      // mat-menu portals to a body-level .cdk-overlay-pane; the menu
      // panel itself carries .mat-mdc-menu-panel.
      await page.locator('app-users button[aria-label="Actions"]').first().click();
      await page.waitForSelector('.mat-mdc-menu-panel');
    }, '.mat-mdc-menu-panel');
  });

  test('/admin/users — Reset Password dialog', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await auditOpenState(page, async () => {
      await page.goto('/admin/users');
      await page.waitForSelector('app-users table');
      await page.locator('app-users button[aria-label="Actions"]').first().click();
      await page.locator('.mat-mdc-menu-panel button', { hasText: 'Reset Password' }).click();
      await page.waitForSelector('app-users .modal-overlay');
    }, 'app-users .modal-overlay');
  });

  test('/admin/users — View Sessions dialog', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await auditOpenState(page, async () => {
      await page.goto('/admin/users');
      await page.waitForSelector('app-users table');
      await page.locator('app-users button[aria-label="Actions"]').first().click();
      await page.locator('.mat-mdc-menu-panel button', { hasText: 'View Sessions' }).click();
      await page.waitForSelector('app-users .modal-overlay .sessions-modal');
    }, 'app-users .modal-overlay');
  });

  test('/admin/tags — New Tag dialog', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await auditOpenState(page, async () => {
      await page.goto('/admin/tags');
      await page.waitForSelector('app-tag-management table');
      await page.locator('app-tag-management button', { hasText: /^\s*add New Tag\s*$/i }).first().click();
      await page.waitForSelector('app-tag-management .modal-overlay');
    }, 'app-tag-management .modal-overlay');
  });

  test('/admin/tags — Delete Tag confirm', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await auditOpenState(page, async () => {
      await page.goto('/admin/tags');
      await page.waitForSelector('app-tag-management table');
      await page.locator('app-tag-management button[aria-label="Delete tag"]').first().click();
      await page.waitForSelector('app-tag-management .modal-overlay');
    }, 'app-tag-management .modal-overlay');
  });

  test('/admin/family-members — Edit Member dialog', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await auditOpenState(page, async () => {
      await page.goto('/admin/family-members');
      await page.waitForSelector('app-family-members table');
      await page.locator('app-family-members button[aria-label="Edit"]').first().click();
      await page.waitForSelector('app-family-members .modal-overlay');
    }, 'app-family-members .modal-overlay');
  });

  test('/admin/family-members — Delete Member confirm', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await auditOpenState(page, async () => {
      await page.goto('/admin/family-members');
      await page.waitForSelector('app-family-members table');
      await page.locator('app-family-members button[aria-label="Delete"]').first().click();
      await page.waitForSelector('app-family-members .modal-overlay .modal-content.small');
    }, 'app-family-members .modal-overlay');
  });
});
