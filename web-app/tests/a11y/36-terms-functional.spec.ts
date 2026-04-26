import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// Functional companion to 03-terms (axe-only). TermsComponent
// loads /api/v2/legal/status — when compliant=true it short-
// circuits to the returnUrl. When compliant=false it shows a
// checkbox-per-document form whose required validators are added
// dynamically based on which URLs are present in the response.
// Submit POSTs /api/v2/legal/agree with the version numbers from
// the legal status response.

async function loadTerms(page: Page) {
  await mockBackend(page, { legalStatus: 'pending' });
  await loginAs(page);
  await page.goto('/terms');
  await page.waitForSelector('app-terms form');
}

test.describe('terms — render + validation', () => {
  test('renders both checkboxes with links to the policy URLs', async ({ page }) => {
    await loadTerms(page);
    const checkboxes = page.locator('app-terms mat-checkbox');
    await expect(checkboxes).toHaveCount(2);
    // Anchor inside the privacy checkbox label.
    await expect(page.locator('app-terms a[href="https://example.com/privacy"]'))
      .toBeVisible();
    await expect(page.locator('app-terms a[href="https://example.com/terms"]'))
      .toBeVisible();
  });

  test('Accept is disabled until both checkboxes are checked', async ({ page }) => {
    await loadTerms(page);
    const submit = page.locator('app-terms button[type="submit"]');
    await expect(submit).toBeDisabled();

    // Check just privacy — still invalid because terms is unchecked.
    await page.locator('app-terms mat-checkbox').first().locator('input').check();
    await expect(submit).toBeDisabled();

    await page.locator('app-terms mat-checkbox').nth(1).locator('input').check();
    await expect(submit).toBeEnabled();
  });

  test('compliant status redirects away without showing the form', async ({ page }) => {
    await mockBackend(page, { legalStatus: 'compliant' });
    await loginAs(page);
    await page.goto('/terms');
    await expect(page).not.toHaveURL(/\/terms/);
  });
});

// -------- Submit --------

async function checkBothBoxes(page: Page) {
  await loadTerms(page);
  await page.locator('app-terms mat-checkbox').first().locator('input').check();
  await page.locator('app-terms mat-checkbox').nth(1).locator('input').check();
}

test.describe('terms — submit', () => {
  test('Accept POSTs /api/v2/legal/agree with the required versions', async ({ page }) => {
    await checkBothBoxes(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/legal/agree'),
      { timeout: 3_000 },
    );
    await page.locator('app-terms button[type="submit"]').click();
    const got = await req;
    const body = got.postDataJSON();
    // status.pending.json: required versions = 2.
    expect(body.privacy_policy_version).toBe(2);
    expect(body.terms_of_use_version).toBe(2);
  });

  test('successful Accept navigates away from /terms', async ({ page }) => {
    await checkBothBoxes(page);
    // Once the user accepts, subsequent guard checks must report
    // compliant or the auth guard loops the navigation back to /terms.
    // Override the route AFTER the form is mounted (LIFO match wins).
    await page.route('**/api/v2/legal/status', r =>
      r.fulfill({ json: { compliant: true } })
    );
    await page.locator('app-terms button[type="submit"]').click();
    await expect(page).not.toHaveURL(/\/terms/);
  });

  test('returnUrl query param is honoured on accept', async ({ page }) => {
    await mockBackend(page, { legalStatus: 'pending' });
    await loginAs(page);
    await page.goto('/terms?returnUrl=%2Fcontent%2Fmovies');
    await page.waitForSelector('app-terms form');
    await page.locator('app-terms mat-checkbox').first().locator('input').check();
    await page.locator('app-terms mat-checkbox').nth(1).locator('input').check();
    // Same compliance flip — without this, /content/movies route guard
    // still sees pending and bounces back to /terms.
    await page.route('**/api/v2/legal/status', r =>
      r.fulfill({ json: { compliant: true } })
    );
    await page.locator('app-terms button[type="submit"]').click();
    await expect(page).toHaveURL(/\/content\/movies/);
  });

  test('agree failure renders the inline error', async ({ page }) => {
    await checkBothBoxes(page);
    await page.route('**/api/v2/legal/agree', r =>
      r.fulfill({ status: 500 })
    );
    await page.locator('app-terms button[type="submit"]').click();
    await expect(page.locator('app-terms .error-message'))
      .toContainText('Failed to record agreement');
  });
});
