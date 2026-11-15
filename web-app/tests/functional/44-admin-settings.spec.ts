import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// /admin/settings — SettingsComponent. Loads settings + buddy-keys
// + is_docker flag, lets admin edit each setting (signal-bound),
// Save POSTs the full settings map. Buddy-key dialog generates a
// new API key (one-time display).

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await page.route('**/api/v2/admin/settings', r => {
    if (r.request().method() === 'POST') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.route('**/api/v2/admin/settings/buddy-keys', r =>
    r.fulfill({ json: { id: 99, name: 'Buddy-Gamma', api_key: 'newly-generated-key' } }));
  await page.route('**/api/v2/admin/settings/buddy-keys/*', r => {
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.goto('/admin/settings');
  await page.waitForSelector('app-settings .settings-page');
}

test.describe('admin settings — display', () => {
  test('renders setting inputs from fixture', async ({ page }) => {
    await setup(page);
    // NAS root field shows the loaded value.
    await expect(page.locator('app-settings input[type="text"]').first()).toBeVisible();
  });

  test('buddy keys table renders fixture rows', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-settings table tbody tr')).toHaveCount(2);
    await expect(page.locator('app-settings')).toContainText('Buddy-Alpha');
    await expect(page.locator('app-settings')).toContainText('Buddy-Beta');
  });
});

test.describe('admin settings — save', () => {
  test('Save button POSTs /settings with the form body', async ({ page }) => {
    await setup(page);
    // The fixture sets is_docker=true → NAS Root + FFmpeg inputs are
    // readonly. Mutate the Public Server URL input (always editable) so
    // the Save button enables.
    await page.locator('app-settings input[aria-label="Public Server URL"]').fill('https://example.com');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/settings'),
      { timeout: 3_000 },
    );
    // Save button lives in the sticky save-row.
    await page.locator('app-settings .save-row button').first().click();
    await req;
  });

  test('a refused save shows the server\'s reason and keeps the edits', async ({ page }) => {
    await setup(page);
    // The server refuses an incomplete legal setup with a 400 that names
    // the fields. Registered after setup(), so it wins for POST.
    const REASON = 'iOS Terms of Use URL is required (about:blank if there is no document). ' +
      'iOS Terms of Use Version must be 1 or more.';
    await page.route('**/api/v2/admin/settings', r => {
      if (r.request().method() === 'POST') {
        return r.fulfill({ status: 400, json: { error: REASON } });
      }
      return r.fallback();
    });

    await page.locator('app-settings input[aria-label="Privacy Policy Version"]').fill('1');
    await page.locator('app-settings .save-row button').first().click();

    const alert = page.locator('app-settings .save-row [role="alert"]');
    await expect(alert).toContainText('Not saved.');
    await expect(alert).toContainText('iOS Terms of Use URL is required');
    // Nothing was saved: the form is still dirty and says so.
    await expect(page.locator('app-settings .dirty-label')).toBeVisible();
    await expect(page.locator('app-settings .saved-label')).toHaveCount(0);
    await expect(page.locator('app-settings input[aria-label="Privacy Policy Version"]')).toHaveValue('1');

    // Editing a field clears the stale message.
    await page.locator('app-settings input[aria-label="iOS Terms of Use URL"]').fill('about:blank');
    await expect(alert).toHaveCount(0);
  });
});

test.describe('admin settings — buddy keys', () => {
  test('Generate / New Key button opens dialog', async ({ page }) => {
    await setup(page);
    await page.locator('app-settings button', { hasText: /(New|Generate).*[Kk]ey/ }).first().click();
    // Dialog opens with name input.
    await expect(page.locator('app-settings .modal-overlay, app-settings dialog')).toBeVisible();
  });

  test('Delete on a buddy key DELETEs /settings/buddy-keys/:id', async ({ page }) => {
    await setup(page);
    page.on('dialog', d => d.accept());
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && /\/settings\/buddy-keys\/1$/.test(r.url()),
      { timeout: 3_000 },
    );
    // Delete is an icon-only button with aria-label.
    await page.locator('app-settings table tbody tr').first()
      .locator('button[aria-label="Delete buddy key"]').click();
    await req;
  });
});
