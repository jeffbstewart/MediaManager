import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// Functional companion to axe/04-change-password. The
// component uses signal-backed local state (no reactive form) +
// inline getters for isValid / passwordMismatch / passwordTooShort.
//
// Submit POSTs /api/v2/profile/change-password { current_password,
// new_password }. The response carries { ok: boolean, error? }.
// Up to 5 failed attempts are shown with a remaining-count, after
// which the inputs disable.

async function loadPage(page: Page) {
  await mockBackend(page, { legalStatus: 'compliant' });
  await loginAs(page);
  await page.goto('/change-password');
  await page.waitForSelector('app-change-password form, app-change-password input');
}

test.describe('change-password — validation', () => {
  test('Change Password is disabled until all rules pass', async ({ page }) => {
    await loadPage(page);
    const submit = page.locator('app-change-password button.submit-btn');
    await expect(submit).toBeDisabled();

    await page.locator('app-change-password input').nth(0).fill('current_pwd');
    await expect(submit).toBeDisabled();

    await page.locator('app-change-password input').nth(1).fill('new_pass');
    await expect(submit).toBeDisabled();

    await page.locator('app-change-password input').nth(2).fill('new_pass');
    await expect(submit).toBeEnabled();
  });

  test('new password under 8 chars keeps Change Password disabled', async ({ page }) => {
    await loadPage(page);
    await page.locator('app-change-password input').nth(0).fill('current_pwd');
    await page.locator('app-change-password input').nth(1).fill('short');
    await page.locator('app-change-password input').nth(2).fill('short');
    // Note: the component's @if(passwordTooShort) <mat-error> never
    // renders because Angular Material's mat-error needs a wrapped
    // FormControl in the invalid state — this component uses signal
    // bindings instead. Tracked separately. Until that's fixed the
    // user-observable signal is just the disabled submit button.
    await expect(page.locator('app-change-password button.submit-btn')).toBeDisabled();
  });

  test('mismatched confirmation keeps Change Password disabled', async ({ page }) => {
    await loadPage(page);
    await page.locator('app-change-password input').nth(0).fill('current_pwd');
    await page.locator('app-change-password input').nth(1).fill('hunter2!');
    await page.locator('app-change-password input').nth(2).fill('hunter3!');
    await expect(page.locator('app-change-password button.submit-btn')).toBeDisabled();
  });
});

// -------- Submit --------

async function fillValid(page: Page) {
  await loadPage(page);
  await page.locator('app-change-password input').nth(0).fill('current_pwd');
  await page.locator('app-change-password input').nth(1).fill('new_password!');
  await page.locator('app-change-password input').nth(2).fill('new_password!');
}

test.describe('change-password — submit', () => {
  test('valid submit POSTs the right payload', async ({ page }) => {
    await fillValid(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/profile/change-password'),
      { timeout: 3_000 },
    );
    await page.locator('app-change-password button.submit-btn').click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({
      current_password: 'current_pwd',
      new_password: 'new_password!',
    });
  });

  test('successful response shows the success block', async ({ page }) => {
    await fillValid(page);
    // The default mock returns { ok: true } already.
    await page.locator('app-change-password button.submit-btn').click();
    await expect(page.locator('app-change-password .success-message'))
      .toContainText('Password changed successfully');
  });

  test('current-password mismatch shows error + decrements remaining attempts', async ({ page }) => {
    await fillValid(page);
    await page.route('**/api/v2/profile/change-password', r =>
      r.fulfill({ json: { ok: false, error: 'Current password is wrong' } })
    );
    await page.locator('app-change-password button.submit-btn').click();
    const errorBox = page.locator('app-change-password .error-message');
    await expect(errorBox).toContainText('Current password is wrong');
    // First failure → remaining = 4 (out of maxAttempts=5).
    await expect(errorBox).toContainText('(4 attempts remaining)');
  });

  test('5 failed attempts disables the inputs and shows the lockout message', async ({ page }) => {
    await fillValid(page);
    await page.route('**/api/v2/profile/change-password', r =>
      r.fulfill({ json: { ok: false, error: 'Wrong' } })
    );
    const submit = page.locator('app-change-password button.submit-btn');
    for (let i = 0; i < 5; i++) {
      // waitForResponse must be armed BEFORE the click, otherwise the
      // mocked POST can resolve in the same microtask as the click and
      // the wait misses it → 30 s timeout.
      const settled = page.waitForResponse(r => r.url().endsWith('/api/v2/profile/change-password'));
      await submit.click();
      await settled;
    }
    await expect(page.locator('app-change-password .error-message'))
      .toContainText(/Too many failed attempts/);
    await expect(page.locator('app-change-password input').nth(0)).toBeDisabled();
  });
});
