import { test } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { auditA11y } from '../helpers/run-axe';

// /help is a static viewer-facing reference page. Pure axe sweep
// — table semantics, heading hierarchy, link text, etc. The sibling
// sibling tests/functional/06-help spec covers structural rendering.

test.describe('/help (axe)', () => {
  test('renders with zero axe violations', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await page.goto('/help');
    await page.waitForSelector('app-help h1');
    await auditA11y(page);
  });
});
