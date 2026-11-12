import { test, expect } from '../helpers/test-fixture';
import AxeBuilder from '@axe-core/playwright';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// Axe sweep for the Report-a-Problem dialog. The dialog is gated
// on the shell's `reportDialogOpen` signal — opened from the
// "Report a Problem" entry in the user sheet.
//
// The audit is scoped to the dialog, so this spec drives axe directly
// instead of the whole-page auditA11y helper.

async function openDialog(page: import('@playwright/test').Page) {
  await mockBackend(page);
  await loginAs(page);
  await page.goto('/');
  await page.waitForSelector('button[aria-label="Open user menu"]');
  await page.locator('button[aria-label="Open user menu"]').click();
  await page.locator('dialog.sheet .sheet-link', { hasText: 'Report a Problem' }).click();
  await page.waitForSelector('app-report-problem-dialog .modal-content');
}

test.describe('report-problem dialog (axe)', () => {
  test('renders with zero axe violations', async ({ page }) => {
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
      'violations:\n' +
        results.violations.map(v => `  ${v.id}: ${v.description}`).join('\n'),
    ).toEqual([]);
  });
});
