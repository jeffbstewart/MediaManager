import { test } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { auditA11y } from '../helpers/run-axe';

// /profile renders the user's account info, password / passkey
// management, hidden-titles list, and active sessions. Run a full
// axe sweep at the default loaded state. The change-password
// modal has its own functional spec (functional/07-profile) — opening
// it here would race the auditA11y reload-per-scheme dance.

test.describe('/profile (axe)', () => {
  test('renders with zero axe violations', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/profile');
    await page.waitForSelector('app-profile h2');
    await auditA11y(page);
  });
});
