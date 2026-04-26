import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// /admin/cameras — CameraSettingsComponent. CDK drag-drop list of
// cameras + per-row enable toggle + delete + Add Camera dialog.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await page.route('**/api/v2/admin/cameras/reorder', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/cameras', r => {
    if (r.request().method() === 'POST') return r.fulfill({ json: { id: 99 } });
    return r.fallback();
  });
  await page.route('**/api/v2/admin/cameras/*', r => {
    const m = r.request().method();
    if (m === 'POST') return r.fulfill({ status: 204 });
    if (m === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.goto('/admin/cameras');
  await page.waitForSelector('app-camera-settings .camera-list, app-camera-settings .empty-text');
}

test.describe('admin cameras — display', () => {
  test('renders 3 fixture cameras with enabled state', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-camera-settings .camera-card, app-camera-settings .camera-row')).toHaveCount(3);
    await expect(page.locator('app-camera-settings')).toContainText('Front Door');
    await expect(page.locator('app-camera-settings')).toContainText('Garage');
  });

  test('go2rtc status badge reflects fixture (running)', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-camera-settings')).toContainText(/running/i);
  });
});

test.describe('admin cameras — actions', () => {
  test('Add Camera button opens the dialog', async ({ page }) => {
    await setup(page);
    await page.locator('app-camera-settings button', { hasText: /Add Camera|New Camera/ }).first().click();
    await expect(page.locator('app-camera-settings .modal-overlay')).toBeVisible();
  });

  test('Delete on a camera DELETEs /cameras/:id (after confirm)', async ({ page }) => {
    await setup(page);
    page.on('dialog', d => d.accept());
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && /\/cameras\/1$/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-camera-settings button[aria-label*="Delete"], app-camera-settings button[title*="Delete"]')
      .first().click();
    await req;
  });
});
