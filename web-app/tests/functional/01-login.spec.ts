import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';

// Functional companion to axe/01-login. LoginComponent
// gates on three things in ngOnInit:
//   - already-authenticated short-circuit (handled by other specs)
//   - silent /auth/refresh — 401 in mockBackend's default makes it
//     fall through to the form
//   - /auth/discover — drives setup_required redirect AND the
//     passkey-button visibility
//
// onSubmit POSTs /api/v2/auth/login; the response can carry
// password_change_required to trigger a /change-password redirect.
// Error 401 surfaces as `error.error` in the JSON body.

test.describe('login — discover branching', () => {
  test('redirects to /setup when discover.setup_required is true', async ({ page }) => {
    await mockBackend(page, { discover: 'setup-required' });
    await page.goto('/login');
    await expect(page).toHaveURL(/\/setup$/);
  });

  test('renders the form when discover is normal + passkeys disabled', async ({ page }) => {
    // Default discover.normal.json has passkeys_available=false.
    await mockBackend(page, { discover: 'normal' });
    await page.goto('/login');
    await page.waitForSelector('app-login form');
    await expect(page.locator('app-login mat-card-title')).toContainText('Sign In');
    // Passkey button is gated on the discover flag — should NOT render.
    await expect(page.locator('app-login button.passkey-button')).toHaveCount(0);
  });

  test('renders the passkey button when discover.passkeys_available is true', async ({ page }) => {
    await mockBackend(page, { discover: 'normal' });
    // Override the default discover with passkeys turned on; the
    // component additionally calls webauthn.isSupported() which is
    // true in headless Chromium.
    await page.route('**/api/v2/auth/discover', r =>
      r.fulfill({ json: {
        setup_required: false, passkeys_available: true,
        legal: { privacy_policy_url: 'https://example.com/p', privacy_policy_version: 1,
                 terms_of_use_url: 'https://example.com/t', terms_of_use_version: 1 },
      } })
    );
    await page.goto('/login');
    await page.waitForSelector('app-login button.passkey-button');
    await expect(page.locator('app-login button.passkey-button')).toContainText(/Sign in with passkey/);
  });

  test('shows "Cannot reach server" when discover errors', async ({ page }) => {
    await mockBackend(page, { discover: 'normal' });
    await page.route('**/api/v2/auth/discover', r => r.fulfill({ status: 500 }));
    await page.goto('/login');
    await expect(page.locator('app-login .error-message')).toContainText('Cannot reach server');
  });
});

// -------- Form validation + submit --------

test.describe('login — form + submit', () => {
  async function loadForm(page: Page) {
    await mockBackend(page, { discover: 'normal' });
    await page.goto('/login');
    await page.waitForSelector('app-login form');
  }

  test('Sign In is disabled until both fields are filled', async ({ page }) => {
    await loadForm(page);
    const submit = page.locator('app-login button[type="submit"]');
    await expect(submit).toBeDisabled();

    await page.locator('app-login input[formcontrolname="username"]').fill('alice');
    await expect(submit).toBeDisabled();

    await page.locator('app-login input[formcontrolname="password"]').fill('hunter2');
    await expect(submit).toBeEnabled();
  });

  test('successful submit POSTs the right payload then navigates home', async ({ page }) => {
    await loadForm(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/auth/login'),
      { timeout: 3_000 },
    );
    await page.locator('app-login input[formcontrolname="username"]').fill('alice');
    await page.locator('app-login input[formcontrolname="password"]').fill('hunter2');
    await page.locator('app-login button[type="submit"]').click();
    const got = await req;
    // The catalog auth.service builds the body — it serialises username +
    // password as a JSON object. Just assert the values made it through.
    const body = got.postDataJSON();
    expect(body.username).toBe('alice');
    expect(body.password).toBe('hunter2');
    // mockBackend's default /api/v2/auth/login fixture does not carry
    // password_change_required, so the component falls through to
    // navigateAway() → '/' (no returnUrl on this load).
    await expect(page).not.toHaveURL(/\/login/);
  });

  test('password_change_required response routes to /change-password', async ({ page }) => {
    await loadForm(page);
    await page.route('**/api/v2/auth/login', r =>
      r.fulfill({ json: { access_token: 't', expires_in: 900, password_change_required: true } })
    );
    await page.locator('app-login input[formcontrolname="username"]').fill('alice');
    await page.locator('app-login input[formcontrolname="password"]').fill('temp_pwd');
    await page.locator('app-login button[type="submit"]').click();
    await expect(page).toHaveURL(/\/change-password$/);
  });

  test('401 with an error message renders that message inline', async ({ page }) => {
    await loadForm(page);
    await page.route('**/api/v2/auth/login', r =>
      r.fulfill({ status: 401, json: { error: 'Invalid credentials' } })
    );
    await page.locator('app-login input[formcontrolname="username"]').fill('alice');
    await page.locator('app-login input[formcontrolname="password"]').fill('wrong');
    await page.locator('app-login button[type="submit"]').click();
    await expect(page.locator('app-login .error-message')).toContainText('Invalid credentials');
    // Form goes back to enabled so the user can retry.
    await expect(page.locator('app-login button[type="submit"]')).toBeEnabled();
  });

  test('rate-limited response surfaces the retry-after countdown text', async ({ page }) => {
    await loadForm(page);
    await page.route('**/api/v2/auth/login', r =>
      r.fulfill({ status: 429, json: { error: 'Too many attempts', retry_after: 30 } })
    );
    await page.locator('app-login input[formcontrolname="username"]').fill('alice');
    await page.locator('app-login input[formcontrolname="password"]').fill('wrong');
    await page.locator('app-login button[type="submit"]').click();
    await expect(page.locator('app-login .error-message'))
      .toContainText('Try again in 30 seconds');
  });
});

// -------- Already-authenticated short-circuit --------

test.describe('login — silent refresh', () => {
  test('valid refresh cookie navigates straight away from /login', async ({ page }) => {
    await mockBackend(page, { discover: 'normal' });
    // Override the default 401 refresh with a populated session — same
    // pattern as loginAs(), but inline so we can assert the redirect
    // direction explicitly.
    await page.route('**/api/v2/auth/refresh', r =>
      r.fulfill({ json: { access_token: 't', expires_in: 900 } })
    );
    await page.goto('/login?returnUrl=%2Fcontent%2Fmovies');
    // returnUrl=/content/movies is honoured by navigateAway().
    await expect(page).toHaveURL(/\/content\/movies/);
  });
});
