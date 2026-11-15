import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// The device-pairing confirmation names the account the device will be
// linked to. A user is known by their login name and nothing else, so
// that is what the badge shows (issue #29: it used to show the display
// name, which now holds a placeholder).

test.describe('pair confirm', () => {
  test('names the account by its login name', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    // A server that still sends the retired field must not have it shown.
    await page.route('**/api/v2/pair/info*', r =>
      r.fulfill({ json: { status: 'pending', display_name: 'TOBEREMOVED' } }));
    await page.goto('/pair?code=ABC123');
    await expect(page.locator('app-pair-confirm .user-badge')).toHaveText('testuser');
    await expect(page.locator('app-pair-confirm')).not.toContainText('TOBEREMOVED');
  });
});
