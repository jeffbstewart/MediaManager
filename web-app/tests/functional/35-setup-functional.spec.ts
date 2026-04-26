import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';

// Functional companion to 02-setup (axe-only). SetupComponent is
// only reachable when /api/v2/auth/discover reports setup_required.
// On submit it POSTs /api/v2/auth/setup with username, password,
// privacy_policy_url, terms_of_use_url. The form has a custom
// password-match validator and a URL pattern validator that accepts
// either an https:// URL or the literal "about:blank".

async function loadSetup(page: Page) {
  await mockBackend(page, { discover: 'setup-required' });
  await page.goto('/setup');
  await page.waitForSelector('app-setup form');
}

test.describe('setup — form validation', () => {
  test('Create Server is disabled when required fields are empty', async ({ page }) => {
    await loadSetup(page);
    // privacyPolicyUrl + termsOfUseUrl default to "about:blank" → those
    // pass validation out of the box. Username + password are blank.
    await expect(page.locator('app-setup button[type="submit"]')).toBeDisabled();
  });

  test('passwords-don\'t-match shows the inline mat-error', async ({ page }) => {
    await loadSetup(page);
    await page.locator('app-setup input[formcontrolname="username"]').fill('admin');
    await page.locator('app-setup input[formcontrolname="password"]').fill('hunter2!');
    const confirm = page.locator('app-setup input[formcontrolname="confirmPassword"]');
    await confirm.fill('hunter3!');
    await confirm.blur();
    await expect(page.locator('app-setup mat-error', { hasText: 'Passwords do not match' }))
      .toBeVisible();
    await expect(page.locator('app-setup button[type="submit"]')).toBeDisabled();
  });

  test('password shorter than 8 chars shows the minlength error', async ({ page }) => {
    await loadSetup(page);
    const password = page.locator('app-setup input[formcontrolname="password"]');
    await password.fill('short');
    await password.blur();
    await expect(page.locator('app-setup mat-error', { hasText: 'at least 8 characters' }))
      .toBeVisible();
  });

  test('non-https privacy URL shows the pattern error', async ({ page }) => {
    await loadSetup(page);
    const url = page.locator('app-setup input[formcontrolname="privacyPolicyUrl"]');
    await url.fill('http://example.com/privacy');
    await url.blur();
    await expect(page.locator('app-setup mat-error', { hasText: 'https:// URL or about:blank' }))
      .toBeVisible();
  });
});

// -------- Submit + navigation --------

async function fillValidForm(page: Page) {
  await loadSetup(page);
  await page.locator('app-setup input[formcontrolname="username"]').fill('admin');
  await page.locator('app-setup input[formcontrolname="password"]').fill('hunter2!');
  await page.locator('app-setup input[formcontrolname="confirmPassword"]').fill('hunter2!');
  // privacyPolicyUrl + termsOfUseUrl already default to about:blank.
}

test.describe('setup — submit', () => {
  test('valid submit POSTs the full payload', async ({ page }) => {
    await fillValidForm(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/auth/setup'),
      { timeout: 3_000 },
    );
    await page.locator('app-setup button[type="submit"]').click();
    const got = await req;
    const body = got.postDataJSON();
    expect(body.username).toBe('admin');
    expect(body.password).toBe('hunter2!');
    expect(body.privacy_policy_url).toBe('about:blank');
    expect(body.terms_of_use_url).toBe('about:blank');
  });

  test('successful setup navigates to home', async ({ page }) => {
    await fillValidForm(page);
    await page.locator('app-setup button[type="submit"]').click();
    await expect(page).not.toHaveURL(/\/setup/);
  });

  test('server error renders the message inline', async ({ page }) => {
    await fillValidForm(page);
    await page.route('**/api/v2/auth/setup', r =>
      r.fulfill({ status: 400, json: { error: 'Username already taken' } })
    );
    await page.locator('app-setup button[type="submit"]').click();
    await expect(page.locator('app-setup .error-message'))
      .toContainText('Username already taken');
  });
});
