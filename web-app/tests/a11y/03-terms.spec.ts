import { test } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { auditA11y } from '../helpers/run-axe';

test.describe('/terms', () => {
  test('renders acceptance form with zero axe violations', async ({ page }) => {
    // termsGuard requires authentication — no legal check (that would
    // loop). Flip legal to pending so the page renders the form instead
    // of immediately redirecting out.
    await mockBackend(page, { legalStatus: 'pending' });
    await loginAs(page);
    await page.goto('/terms');
    await page.waitForSelector('form');
    await auditA11y(page);
  });
});
