import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// /admin/users — UsersComponent. Renders a mat-table of all users
// with per-row Actions menu (Promote/Demote/Unlock/Force PW Change/
// Reset PW/Set Rating/View Sessions/Delete) plus an "Add User" button
// that opens a modal with a sign-up-like form.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  // Stub all per-user mutation endpoints — return ok so the awaited
  // POSTs/DELETEs resolve.
  await page.route('**/api/v2/admin/users/*/promote', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/users/*/demote', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/users/*/unlock', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/users/*/force-password-change', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/users/*/rating-ceiling', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/users/*/reset-password', r => r.fulfill({ json: { ok: true } }));
  await page.route('**/api/v2/admin/users/*/revoke-all-sessions', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/users/*/sessions/*', r => {
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.route('**/api/v2/admin/users/*', r => {
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  // Add-user POST goes to the bare /api/v2/admin/users (collides with
  // the GET that mockBackend already wires) — split by method.
  await page.route('**/api/v2/admin/users', r => {
    if (r.request().method() === 'POST') return r.fulfill({ json: { ok: true } });
    return r.fallback();
  });
  await page.goto('/admin/users');
  await page.waitForSelector('app-users table');
}

async function openMenu(page: Page, rowIdx: number) {
  await page.locator('app-users button[aria-label="Actions"]').nth(rowIdx).click();
  await page.waitForSelector('.mat-mdc-menu-panel');
}

test.describe('admin users — display', () => {
  test('renders all 3 fixture users with username + role', async ({ page }) => {
    await setup(page);
    const rows = page.locator('app-users table tbody tr');
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0).locator('.username')).toContainText('admin');
    await expect(rows.nth(0).locator('.role-badge')).toContainText('Admin');
    await expect(rows.nth(1).locator('.username')).toContainText('viewer1');
    await expect(rows.nth(1).locator('.role-badge')).toContainText('Viewer');
  });

  test('locked + pw-change badges render on the right rows', async ({ page }) => {
    await setup(page);
    // viewer1 has must_change_password=true; locked-user has locked=true.
    await expect(page.locator('app-users tr', { hasText: 'viewer1' }).locator('.status-badge.pw-change')).toBeVisible();
    await expect(page.locator('app-users tr', { hasText: 'locked-user' }).locator('.status-badge.locked')).toBeVisible();
    // admin has neither.
    await expect(page.locator('app-users tr', { hasText: /^.*admin/ }).first().locator('.status-badge')).toHaveCount(0);
  });

  test('rating-ceiling label resolves to the right human label', async ({ page }) => {
    await setup(page);
    // viewer1.rating_ceiling=3 → "PG / TV-PG"; admin null → "Unrestricted".
    await expect(page.locator('app-users tr', { hasText: 'viewer1' })).toContainText('PG / TV-PG');
    await expect(page.locator('app-users tr', { hasText: /admin\s/ }).first()).toContainText('Unrestricted');
  });
});

test.describe('admin users — Add User dialog', () => {
  test('opens + closes via Cancel', async ({ page }) => {
    await setup(page);
    await page.locator('app-users button', { hasText: 'Add User' }).click();
    await expect(page.locator('app-users .modal-overlay')).toBeVisible();
    await page.locator('app-users .modal-overlay button', { hasText: 'Cancel' }).click();
    await expect(page.locator('app-users .modal-overlay')).toHaveCount(0);
  });

  test('Create disabled until all fields valid + matching', async ({ page }) => {
    await setup(page);
    await page.locator('app-users button', { hasText: 'Add User' }).click();
    const create = page.locator('app-users .modal-overlay button', { hasText: 'Create' });
    await expect(create).toBeDisabled();
    await page.locator('app-users #add-user-username').fill('alice');
    await expect(create).toBeDisabled();
    await page.locator('app-users #add-user-password').fill('hunter2!');
    await page.locator('app-users #add-user-confirm').fill('hunter3!');
    await expect(create).toBeDisabled();
    await page.locator('app-users #add-user-confirm').fill('hunter2!');
    await expect(create).toBeEnabled();
  });

  test('submit POSTs username + password + force_change', async ({ page }) => {
    await setup(page);
    await page.locator('app-users button', { hasText: 'Add User' }).click();
    await page.locator('app-users #add-user-username').fill('alice');
    await page.locator('app-users #add-user-password').fill('hunter2!');
    await page.locator('app-users #add-user-confirm').fill('hunter2!');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/users'),
      { timeout: 3_000 },
    );
    await page.locator('app-users .modal-overlay button', { hasText: 'Create' }).click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({
      username: 'alice', password: 'hunter2!', force_change: true,
    });
  });
});

test.describe('admin users — actions menu', () => {
  test('Viewer row shows Promote (not Demote)', async ({ page }) => {
    await setup(page);
    await openMenu(page, 1);
    await expect(page.locator('.mat-mdc-menu-panel button', { hasText: 'Promote to Admin' })).toBeVisible();
    await expect(page.locator('.mat-mdc-menu-panel button', { hasText: 'Demote to Viewer' })).toHaveCount(0);
  });

  test('Admin row shows Demote (not Promote)', async ({ page }) => {
    await setup(page);
    await openMenu(page, 0);
    await expect(page.locator('.mat-mdc-menu-panel button', { hasText: 'Demote to Viewer' })).toBeVisible();
    await expect(page.locator('.mat-mdc-menu-panel button', { hasText: 'Promote to Admin' })).toHaveCount(0);
  });

  test('Unlock only renders on locked users', async ({ page }) => {
    await setup(page);
    // Row 2 is locked-user.
    await openMenu(page, 2);
    await expect(page.locator('.mat-mdc-menu-panel button', { hasText: 'Unlock' })).toBeVisible();
  });

  test('Promote POSTs /promote', async ({ page }) => {
    await setup(page);
    await openMenu(page, 1);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/users/2/promote'),
      { timeout: 3_000 },
    );
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Promote to Admin' }).click();
    await req;
  });

  test('Set Rating Ceiling submenu POSTs the chosen value', async ({ page }) => {
    await setup(page);
    await openMenu(page, 1);
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Set Rating Ceiling' }).click();
    // Submenu opens; pick PG-13/TV-14 (value 4).
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/users/2/rating-ceiling'),
      { timeout: 3_000 },
    );
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'PG-13 / TV-14' }).click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({ rating_ceiling: 4 });
  });

  test('Delete prompts confirm and DELETEs the user', async ({ page }) => {
    await setup(page);
    page.on('dialog', d => d.accept());
    await openMenu(page, 1);
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/admin/users/2'),
      { timeout: 3_000 },
    );
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Delete User' }).click();
    await req;
  });
});

test.describe('admin users — Reset Password dialog', () => {
  test('opens via menu, Reset disabled until valid + matching', async ({ page }) => {
    await setup(page);
    await openMenu(page, 1);
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Reset Password' }).click();
    await expect(page.locator('app-users .modal-overlay')).toBeVisible();
    const reset = page.locator('app-users .modal-overlay button', { hasText: /^Reset$/ });
    await expect(reset).toBeDisabled();
    await page.locator('app-users #reset-user-password').fill('newpass!');
    await page.locator('app-users #reset-user-confirm').fill('newpass!');
    await expect(reset).toBeEnabled();
  });

  test('submit POSTs /reset-password with the password body', async ({ page }) => {
    await setup(page);
    await openMenu(page, 1);
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Reset Password' }).click();
    await page.locator('app-users #reset-user-password').fill('newpass!');
    await page.locator('app-users #reset-user-confirm').fill('newpass!');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/users/2/reset-password'),
      { timeout: 3_000 },
    );
    await page.locator('app-users .modal-overlay button', { hasText: /^Reset$/ }).click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({ password: 'newpass!' });
  });
});

test.describe('admin users — Sessions dialog', () => {
  test('opens, lists fixture sessions, Revoke DELETEs the session', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    // Override the per-user sessions endpoint to return populated rows
    // (mockBackend defaults to empty).
    await page.route('**/api/v2/admin/users/*/sessions', r =>
      r.fulfill({ json: { sessions: [
        { id: 10, type: 'browser', user_agent: 'Mozilla/5.0', last_used_at: '2026-04-24T09:00:00Z' },
        { id: 11, type: 'device', device_name: 'iPhone', last_used_at: '2026-04-23T18:00:00Z' },
      ] } }));
    await page.route('**/api/v2/admin/users/*/sessions/*', r => {
      if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
      return r.fallback();
    });
    await page.goto('/admin/users');
    await page.waitForSelector('app-users table');
    await page.locator('app-users button[aria-label="Actions"]').nth(0).click();
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'View Sessions' }).click();
    await expect(page.locator('app-users .sessions-modal')).toBeVisible();
    await expect(page.locator('app-users .session-row')).toHaveCount(2);

    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/admin/users/1/sessions/10'),
      { timeout: 3_000 },
    );
    await page.locator('app-users .session-row').first().locator('button', { hasText: 'Revoke' }).click();
    await req;
    await expect(page.locator('app-users .session-row')).toHaveCount(1);
  });

  test('Revoke All Sessions POSTs /revoke-all-sessions', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await page.route('**/api/v2/admin/users/*/sessions', r =>
      r.fulfill({ json: { sessions: [
        { id: 10, type: 'browser', last_used_at: null },
      ] } }));
    await page.route('**/api/v2/admin/users/*/revoke-all-sessions', r =>
      r.fulfill({ status: 204 }));
    await page.goto('/admin/users');
    await page.waitForSelector('app-users table');
    await page.locator('app-users button[aria-label="Actions"]').nth(0).click();
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'View Sessions' }).click();
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/users/1/revoke-all-sessions'),
      { timeout: 3_000 },
    );
    await page.locator('app-users .revoke-all-btn').click();
    await req;
  });
});
