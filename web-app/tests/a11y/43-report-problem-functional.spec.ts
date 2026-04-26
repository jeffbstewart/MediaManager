import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Functional spec for the Report-a-Problem dialog. The dialog is
// mounted in the shell and opened from the user-profile menu's
// "Report a Problem" button. The dialog POSTs /api/v2/reports
// when the user submits a non-empty description; on success the
// inner content swaps to the "Report submitted. Thank you!" pane.
//
// When opened from a /title/:id route, the shell pre-populates
// titleId + titleName so the dialog renders a "Title" context row.

async function setup(page: Page) {
  await mockBackend(page);
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/reports', r =>
    r.fulfill({ json: { ok: true } })
  );
}

async function openFromMenu(page: Page) {
  await page.locator('button[aria-label="Profile menu"]').click();
  await page.locator('button[mat-menu-item]', { hasText: 'Report a Problem' }).click();
  await page.waitForSelector('app-report-problem-dialog .modal-content');
}

test.describe('report-problem — open / close', () => {
  test('Report a Problem menu item opens the dialog', async ({ page }) => {
    await setup(page);
    await page.goto('/');
    await openFromMenu(page);
    await expect(page.locator('app-report-problem-dialog .modal-overlay')).toBeVisible();
    await expect(page.locator('app-report-problem-dialog h3')).toContainText('Report a Problem');
  });

  test('Cancel button closes the dialog', async ({ page }) => {
    await setup(page);
    await page.goto('/');
    await openFromMenu(page);
    await page.locator('app-report-problem-dialog button', { hasText: 'Cancel' }).click();
    await expect(page.locator('app-report-problem-dialog .modal-overlay')).toHaveCount(0);
  });

  test('Close (×) button closes the dialog', async ({ page }) => {
    await setup(page);
    await page.goto('/');
    await openFromMenu(page);
    await page.locator('app-report-problem-dialog button[aria-label="Close"]').click();
    await expect(page.locator('app-report-problem-dialog .modal-overlay')).toHaveCount(0);
  });

  test('clicking the overlay (outside the modal) closes the dialog', async ({ page }) => {
    await setup(page);
    await page.goto('/');
    await openFromMenu(page);
    // Click in the corner of the overlay — the inner .modal-content
    // stops propagation so this only fires the overlay handler.
    await page.locator('app-report-problem-dialog .modal-overlay').click({
      position: { x: 5, y: 5 },
    });
    await expect(page.locator('app-report-problem-dialog .modal-overlay')).toHaveCount(0);
  });
});

// -------- Submit --------

test.describe('report-problem — submit', () => {
  test('Submit is disabled until the description has non-whitespace text', async ({ page }) => {
    await setup(page);
    await page.goto('/');
    await openFromMenu(page);

    const submit = page.locator('app-report-problem-dialog button', { hasText: 'Submit' });
    await expect(submit).toBeDisabled();

    await page.locator('app-report-problem-dialog textarea').fill('   ');
    await expect(submit).toBeDisabled();

    await page.locator('app-report-problem-dialog textarea').fill('Playback freezes after 5 minutes.');
    await expect(submit).toBeEnabled();
  });

  test('Submit POSTs /api/v2/reports with description + null context fields', async ({ page }) => {
    await setup(page);
    await page.goto('/');
    await openFromMenu(page);
    await page.locator('app-report-problem-dialog textarea').fill('Crash on Live TV channel 7.1');

    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/reports'),
      { timeout: 3_000 },
    );
    await page.locator('app-report-problem-dialog button', { hasText: 'Submit' }).click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({
      title_id: null,
      title_name: null,
      season_number: null,
      episode_number: null,
      description: 'Crash on Live TV channel 7.1',
    });
  });

  test('successful submit swaps to the "Report submitted" pane', async ({ page }) => {
    await setup(page);
    await page.goto('/');
    await openFromMenu(page);
    await page.locator('app-report-problem-dialog textarea').fill('Playback freezes after 5 minutes.');
    await page.locator('app-report-problem-dialog button', { hasText: 'Submit' }).click();
    await expect(page.locator('app-report-problem-dialog .done-message'))
      .toContainText('Report submitted');
    // Submit + Cancel are gone; only the inner Close button remains.
    await expect(page.locator('app-report-problem-dialog button', { hasText: 'Submit' }))
      .toHaveCount(0);
    await expect(page.locator('app-report-problem-dialog .done-message button', { hasText: 'Close' }))
      .toBeVisible();
  });

  test('failed submit keeps the dialog open so the user can retry', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await page.route('**/api/v2/reports', r => r.fulfill({ status: 500 }));
    await page.goto('/');
    await openFromMenu(page);
    await page.locator('app-report-problem-dialog textarea').fill('Crash on Live TV channel 7.1');
    await page.locator('app-report-problem-dialog button', { hasText: 'Submit' }).click();
    // Dialog stays open and submit is enabled again so a retry is possible.
    await expect(page.locator('app-report-problem-dialog .modal-overlay')).toBeVisible();
    await expect(page.locator('app-report-problem-dialog .done-message')).toHaveCount(0);
  });
});

// -------- Title context auto-population --------

test.describe('report-problem — title context', () => {
  test('opened from /title/:id pre-fills the Title context row in the dialog', async ({ page }) => {
    await setup(page);
    // mockBackend's title-detail fixture for id=100 is "The Matrix".
    await page.goto('/title/100');
    await page.waitForSelector('app-title-detail');
    await openFromMenu(page);
    await expect(page.locator('app-report-problem-dialog .context-display'))
      .toContainText('The Matrix');
  });

  test('Submit from /title/:id includes title_id + title_name in the payload', async ({ page }) => {
    await setup(page);
    await page.goto('/title/100');
    await page.waitForSelector('app-title-detail');
    await openFromMenu(page);
    await page.locator('app-report-problem-dialog textarea').fill('Audio is out of sync.');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/reports'),
      { timeout: 3_000 },
    );
    await page.locator('app-report-problem-dialog button', { hasText: 'Submit' }).click();
    const got = await req;
    const body = got.postDataJSON();
    expect(body.title_id).toBe(100);
    expect(body.title_name).toBe('The Matrix');
    expect(body.description).toBe('Audio is out of sync.');
  });
});
