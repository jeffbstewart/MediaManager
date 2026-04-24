import { test } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { auditA11y } from '../helpers/run-axe';

test.describe('/setup', () => {
  test('renders with zero axe violations when setup is required', async ({ page }) => {
    // setupGuard calls discover() and only admits when setup_required is true.
    await mockBackend(page, { discover: 'setup-required' });
    await page.goto('/setup');
    await page.waitForSelector('form');
    await auditA11y(page);
  });
});
