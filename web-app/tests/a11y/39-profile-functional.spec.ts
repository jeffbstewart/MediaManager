import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Functional spec for /profile (ProfileComponent). Surfaces:
//   - account info (username, display name, role, rating ceiling)
//   - live-tv quality star picker (only when has_live_tv)
//   - change-password modal (separate from /change-password page)
//   - passkeys (gated on passkeys_enabled + webauthn support)
//   - hidden titles (only when non-empty)
//   - active sessions with revoke + revoke-others
//
// All mutation endpoints stubbed inline so awaited posts/deletes
// resolve. Default fixtures cover one current browser session +
// one device session, has_live_tv=true, no passkeys, no hidden
// titles.

async function setup(page: Page) {
  await mockBackend(page);
  await loginAs(page);
  await stubImages(page);
  // Mutation endpoints — return 204 / { ok: true } so the awaited
  // promises resolve without blowing up.
  await page.route('**/api/v2/profile/tv-quality', r =>
    r.fulfill({ status: 204 })
  );
  await page.route('**/api/v2/profile/sessions/revoke-others', r =>
    r.fulfill({ status: 204 })
  );
  await page.route('**/api/v2/profile/sessions/*', r => {
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.route('**/api/v2/profile/hidden-titles/*', r => {
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.route('**/api/v2/profile/change-password', r =>
    r.fulfill({ json: { ok: true } })
  );
  await page.goto('/profile');
  await page.waitForSelector('app-profile h2');
}

test.describe('profile — account info', () => {
  test('renders username / display name / role from the fixture', async ({ page }) => {
    await setup(page);
    const rows = page.locator('app-profile .info-row');
    await expect(rows.nth(0).locator('.info-value')).toContainText('testuser');
    await expect(rows.nth(1).locator('.info-value')).toContainText('Test User');
    await expect(rows.nth(2).locator('.info-value')).toContainText('Viewer');
  });

  test('rating ceiling row is hidden when null', async ({ page }) => {
    await setup(page);
    // Default fixture rating_ceiling=null → only 3 info-rows.
    await expect(page.locator('app-profile .info-row')).toHaveCount(3);
  });

  test('rating ceiling row renders when set', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route('**/api/v2/profile', r =>
      r.fulfill({ json: {
        id: 1, username: 'testuser', display_name: 'Test User',
        is_admin: false, rating_ceiling: 'PG-13', live_tv_min_quality: 0,
        has_live_tv: true, passkeys_enabled: false,
      } })
    );
    await page.goto('/profile');
    await page.waitForSelector('app-profile .info-row');
    await expect(page.locator('app-profile .info-row')).toHaveCount(4);
    await expect(page.locator('app-profile .info-row').nth(3).locator('.info-value'))
      .toContainText('PG-13');
  });
});

// -------- Live TV quality picker --------

test.describe('profile — live TV quality', () => {
  test('5 star buttons render when has_live_tv is true', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-profile .star-btn')).toHaveCount(5);
  });

  test('clicking a star POSTs /tv-quality with the star number', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/profile/tv-quality'),
      { timeout: 3_000 },
    );
    await page.locator('app-profile .star-btn').nth(2).click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({ quality: 3 });
  });

  test('star section is hidden when has_live_tv is false', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await page.route('**/api/v2/profile', r =>
      r.fulfill({ json: {
        id: 1, username: 'testuser', display_name: 'Test User',
        is_admin: false, rating_ceiling: null, live_tv_min_quality: 0,
        has_live_tv: false, passkeys_enabled: false,
      } })
    );
    await page.goto('/profile');
    await page.waitForSelector('app-profile h2');
    await expect(page.locator('app-profile .star-btn')).toHaveCount(0);
    await expect(page.locator('app-profile h3', { hasText: 'Live TV' })).toHaveCount(0);
  });
});

// -------- Change password modal --------

test.describe('profile — change password modal', () => {
  test('Change Password button opens the modal; close button closes it', async ({ page }) => {
    await setup(page);
    await page.locator('app-profile button.action-btn', { hasText: 'Change Password' }).click();
    await expect(page.locator('app-profile .modal-overlay')).toBeVisible();
    await page.locator('app-profile .modal-content button.close-btn').click();
    await expect(page.locator('app-profile .modal-overlay')).toHaveCount(0);
  });

  test('submit POSTs /api/v2/profile/change-password with the right body', async ({ page }) => {
    await setup(page);
    await page.locator('app-profile button.action-btn', { hasText: 'Change Password' }).click();
    const inputs = page.locator('app-profile .modal-content input.pw-input');
    await inputs.nth(0).fill('current_pwd');
    await inputs.nth(1).fill('new_password!');
    await inputs.nth(2).fill('new_password!');

    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/profile/change-password'),
      { timeout: 3_000 },
    );
    await page.locator('app-profile .modal-content button.action-btn.primary').click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({
      current_password: 'current_pwd',
      new_password: 'new_password!',
    });
    await expect(page.locator('app-profile .pw-success')).toBeVisible();
  });

  test('mismatched confirm keeps Change Password disabled', async ({ page }) => {
    await setup(page);
    await page.locator('app-profile button.action-btn', { hasText: 'Change Password' }).click();
    const inputs = page.locator('app-profile .modal-content input.pw-input');
    await inputs.nth(0).fill('current_pwd');
    await inputs.nth(1).fill('hunter2!');
    await inputs.nth(2).fill('hunter3!');
    await expect(page.locator('app-profile .modal-content button.action-btn.primary')).toBeDisabled();
  });

  test('error response renders the inline error message', async ({ page }) => {
    await setup(page);
    await page.route('**/api/v2/profile/change-password', r =>
      r.fulfill({ json: { ok: false, error: 'Wrong current password' } })
    );
    await page.locator('app-profile button.action-btn', { hasText: 'Change Password' }).click();
    const inputs = page.locator('app-profile .modal-content input.pw-input');
    await inputs.nth(0).fill('wrong');
    await inputs.nth(1).fill('new_password!');
    await inputs.nth(2).fill('new_password!');
    await page.locator('app-profile .modal-content button.action-btn.primary').click();
    await expect(page.locator('app-profile .modal-content .pw-error'))
      .toContainText('Wrong current password');
  });
});

// -------- Sessions --------

test.describe('profile — sessions', () => {
  test('renders both fixture sessions with the right badges', async ({ page }) => {
    await setup(page);
    const rows = page.locator('app-profile .session-row');
    await expect(rows).toHaveCount(2);
    // Row 0: current browser session.
    await expect(rows.nth(0).locator('.session-badge')).toContainText('Browser');
    await expect(rows.nth(0).locator('.current-badge')).toContainText('Current');
    await expect(rows.nth(0).locator('button.revoke-btn')).toHaveCount(0);
    // Row 1: device session, revocable.
    await expect(rows.nth(1).locator('.session-badge')).toContainText('Device');
    await expect(rows.nth(1).locator('.current-badge')).toHaveCount(0);
    await expect(rows.nth(1).locator('button.revoke-btn')).toBeVisible();
  });

  test('revoke on a non-current session DELETEs /sessions/:id and removes the row', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/profile/sessions/2'),
      { timeout: 3_000 },
    );
    await page.locator('app-profile .session-row').nth(1).locator('button.revoke-btn').click();
    await req;
    await expect(page.locator('app-profile .session-row')).toHaveCount(1);
  });

  test('Revoke All Other Sessions button shows when >1 session AND POSTs /revoke-others', async ({ page }) => {
    await setup(page);
    const btn = page.locator('app-profile button.action-btn.danger', { hasText: 'Revoke All Other' });
    await expect(btn).toBeVisible();
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/profile/sessions/revoke-others'),
      { timeout: 3_000 },
    );
    await btn.click();
    await req;
    // Component filters down to is_current sessions only.
    await expect(page.locator('app-profile .session-row')).toHaveCount(1);
  });
});

// -------- Hidden titles --------

test.describe('profile — hidden titles', () => {
  test('section is hidden when no hidden titles exist', async ({ page }) => {
    await setup(page);
    // Default fixture: titles=[].
    await expect(page.locator('app-profile h3', { hasText: /^Hidden Titles$/ })).toHaveCount(0);
  });

  test('renders rows + unhide DELETEs /hidden-titles/:id and removes the row', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route('**/api/v2/profile/hidden-titles/*', r => {
      if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
      return r.fallback();
    });
    await page.route('**/api/v2/profile/hidden-titles', r =>
      r.fulfill({ json: { titles: [
        { title_id: 100, title_name: 'The Matrix', poster_url: '/posters/w185/100', release_year: 1999 },
      ] } })
    );
    await page.goto('/profile');
    await page.waitForSelector('app-profile .hidden-title-row');
    await expect(page.locator('app-profile .hidden-title-row')).toHaveCount(1);
    await expect(page.locator('app-profile .hidden-title-link')).toContainText('The Matrix');

    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/profile/hidden-titles/100'),
      { timeout: 3_000 },
    );
    await page.locator('app-profile button.unhide-btn').click();
    await req;
    await expect(page.locator('app-profile .hidden-title-row')).toHaveCount(0);
  });
});
