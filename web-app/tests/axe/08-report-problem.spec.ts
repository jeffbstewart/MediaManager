import { test, expect } from '../helpers/test-fixture';
import AxeBuilder from '@axe-core/playwright';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// Axe sweep for the Report-a-Problem dialog. The dialog is gated
// on the shell's `reportDialogOpen` signal — opened from the
// "Report a Problem" entry in the user-profile menu.
//
// The shared auditA11y helper reloads the page on each color-scheme
// switch, which would close the dialog. This spec drives axe
// directly per scheme so the dialog stays open.

async function openDialog(page: import('@playwright/test').Page) {
  await mockBackend(page);
  await loginAs(page);
  await page.goto('/');
  await page.waitForSelector('button[aria-label="Profile menu"]');
  await page.locator('button[aria-label="Profile menu"]').click();
  await page.locator('button[mat-menu-item]', { hasText: 'Report a Problem' }).click();
  await page.waitForSelector('app-report-problem-dialog .modal-content');
}

test.describe('report-problem dialog (axe)', () => {
  test('renders with zero color-contrast violations in light + dark', async ({ page }) => {
    for (const scheme of ['light', 'dark'] as const) {
      await page.emulateMedia({ colorScheme: scheme });
      await openDialog(page);
      await page.evaluate(() => new Promise<void>(resolve =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
      ));
      const results = await new AxeBuilder({ page })
        .include('app-report-problem-dialog .modal-content')
        // Match the shared auditA11y exclusion — Material's font-icon
        // contrast is reliably high but axe mis-resolves the tokenised
        // color CSS-custom-property fallbacks for these elements.
        .exclude('mat-icon')
        .analyze();
      expect(
        results.violations,
        `${scheme} mode violations:\n` +
          results.violations.map(v => `  ${v.id}: ${v.description}`).join('\n'),
      ).toEqual([]);
    }
  });
});
