import { test } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { auditA11y } from '../helpers/run-axe';

test.describe('/login', () => {
  test('renders with zero axe violations (light/dark × desktop/mobile)', async ({ page }) => {
    await mockBackend(page, { discover: 'normal' });
    await page.goto('/login');
    // Wait for the login card to paint — ngOnInit runs discover() + tryRefresh()
    // before the form is usable; auditing before then would catch the
    // mid-hydration state.
    await page.waitForSelector('form');
    await auditA11y(page);
  });
});
