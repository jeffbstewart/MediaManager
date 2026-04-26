import { test } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { auditA11y } from '../helpers/run-axe';

test.describe('/change-password', () => {
  test('renders with zero axe violations', async ({ page }) => {
    // authGuard gates this route: must be authenticated AND legally
    // compliant. loginAs + compliant status satisfies both.
    await mockBackend(page, { legalStatus: 'compliant' });
    await loginAs(page);
    await page.goto('/change-password');
    await page.waitForSelector('input[type="password"]');
    await auditA11y(page);
  });
});
