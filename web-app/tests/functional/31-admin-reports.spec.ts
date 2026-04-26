import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// /admin/reports — ReportsComponent. Open/Resolved/Dismissed/All
// status chips, mat-table of reports, per-row Actions menu with
// Resolve / Dismiss / Reopen / Delete-catalog-entry, and two
// destination dialogs.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await page.route('**/api/v2/admin/reports/*/resolve', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/reports/*/reopen', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/reports/*/delete-media', r => r.fulfill({ status: 204 }));
  await page.goto('/admin/reports');
  await page.waitForSelector('app-reports table');
}

test.describe('admin reports — display', () => {
  test('renders the OPEN-status fixture row by default', async ({ page }) => {
    await setup(page);
    // Default chip is OPEN; fixture has 1 OPEN row + 1 RESOLVED.
    // Server filters by status, but mockBackend returns the full
    // fixture regardless; the table renders all returned rows.
    await expect(page.locator('app-reports tbody tr')).toHaveCount(2);
    await expect(page.locator('app-reports tbody tr').first().locator('.title-link'))
      .toContainText('The Matrix');
  });

  test('S/E badge renders on TV episode reports', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-reports tr', { hasText: 'Breaking Bad' }).locator('.episode-info'))
      .toContainText('S2E5');
  });

  test('Resolved row has the resolved status badge', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-reports tr', { hasText: 'Breaking Bad' })
      .locator('.status-badge.status-resolved')).toBeVisible();
  });
});

test.describe('admin reports — filter chips', () => {
  test('clicking Resolved fires GET with status=RESOLVED', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/reports') && /status=RESOLVED/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-reports mat-chip', { hasText: /^Resolved$/ }).click();
    await req;
  });

  test('All fires GET with no status param', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r => {
      if (!r.url().includes('/api/v2/admin/reports')) return false;
      return !new URL(r.url()).searchParams.has('status');
    }, { timeout: 3_000 });
    await page.locator('app-reports mat-chip', { hasText: /^All$/ }).click();
    await req;
  });
});

test.describe('admin reports — actions', () => {
  test('Open row\'s Actions menu includes Resolve + Dismiss + Delete-catalog-entry', async ({ page }) => {
    await setup(page);
    await page.locator('app-reports tr', { hasText: 'The Matrix' })
      .locator('button[aria-label="Actions"]').click();
    // mat-menu-item buttons render as "<icon-text> <label>" so anchored
    // ^...$ regex doesn't match. Use plain substring matching here.
    await expect(page.locator('.mat-mdc-menu-panel button', { hasText: 'Resolve' })).toBeVisible();
    await expect(page.locator('.mat-mdc-menu-panel button', { hasText: 'Dismiss' })).toBeVisible();
    await expect(page.locator('.mat-mdc-menu-panel button', { hasText: 'Delete catalog entry' })).toBeVisible();
  });

  test('Resolved row\'s menu shows Reopen instead', async ({ page }) => {
    await setup(page);
    await page.locator('app-reports tr', { hasText: 'Breaking Bad' })
      .locator('button[aria-label="Actions"]').click();
    await expect(page.locator('.mat-mdc-menu-panel button', { hasText: 'Reopen' })).toBeVisible();
  });

  test('Reopen POSTs /reopen', async ({ page }) => {
    await setup(page);
    await page.locator('app-reports tr', { hasText: 'Breaking Bad' })
      .locator('button[aria-label="Actions"]').click();
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/reports/2/reopen'),
      { timeout: 3_000 },
    );
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Reopen' }).click();
    await req;
  });
});

test.describe('admin reports — Resolve dialog', () => {
  test('opens, submit POSTs /resolve with status + notes', async ({ page }) => {
    await setup(page);
    await page.locator('app-reports tr', { hasText: 'The Matrix' })
      .locator('button[aria-label="Actions"]').click();
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Resolve' }).click();
    const dialog = page.locator('app-reports .modal-overlay');
    await expect(dialog.locator('h3')).toContainText('Resolve Report');
    await dialog.locator('textarea').fill('Re-encoded source.');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/reports/1/resolve'),
      { timeout: 3_000 },
    );
    // The dialog footer renders the Resolve label inside whitespace
    // from the template's pretty-printed interpolation; use .last()
    // to disambiguate from the Cancel button (footer has 2 buttons).
    await dialog.locator('button.mat-mdc-unelevated-button').click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({ status: 'RESOLVED', notes: 'Re-encoded source.' });
  });

  test('Dismiss path sends status=DISMISSED', async ({ page }) => {
    await setup(page);
    await page.locator('app-reports tr', { hasText: 'The Matrix' })
      .locator('button[aria-label="Actions"]').click();
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Dismiss' }).click();
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/reports/1/resolve'),
      { timeout: 3_000 },
    );
    // Dialog footer's primary button is the Dismiss submitter (Cancel
    // is mat-button, the primary is mat-flat-button=mdc-unelevated).
    await page.locator('app-reports .modal-overlay button.mat-mdc-unelevated-button').click();
    const got = await req;
    expect(got.postDataJSON().status).toBe('DISMISSED');
  });
});

test.describe('admin reports — Delete catalog entry dialog', () => {
  test('opens with the warn copy', async ({ page }) => {
    await setup(page);
    await page.locator('app-reports tr', { hasText: 'The Matrix' })
      .locator('button[aria-label="Actions"]').click();
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Delete catalog entry' }).click();
    const dialog = page.locator('app-reports .modal-overlay');
    await expect(dialog.locator('h3')).toContainText('Delete catalog entry');
    await expect(dialog).toContainText('Files on the NAS are NOT deleted');
  });

  test('submit POSTs /delete-media with notes', async ({ page }) => {
    await setup(page);
    await page.locator('app-reports tr', { hasText: 'The Matrix' })
      .locator('button[aria-label="Actions"]').click();
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Delete catalog entry' }).click();
    await page.locator('app-reports .modal-overlay textarea').fill('CD scratched');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/reports/1/delete-media'),
      { timeout: 3_000 },
    );
    await page.locator('app-reports .modal-overlay button', { hasText: 'Delete catalog entry' }).click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({ notes: 'CD scratched' });
  });
});
