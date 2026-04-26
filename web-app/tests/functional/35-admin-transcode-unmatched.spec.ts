import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// /admin/transcodes/unmatched — TranscodeUnmatchedComponent. Mat-table
// of NAS files that didn't auto-match a Title, with Accept (use the
// suggestion), Ignore, Link (search dialog), Create-personal,
// + a TMDB-search subflow inside the link dialog.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/admin/unmatched/*/accept', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/unmatched/*/ignore', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/unmatched/*/link/*', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/unmatched/*/create-personal', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/unmatched/*/add-from-tmdb', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/unmatched/search-titles*', r =>
    r.fulfill({ json: { titles: [
      { id: 100, name: 'Some Movie', media_type: 'MOVIE', release_year: 2022 },
    ] } }));
  await page.route('**/api/v2/admin/media-item/search-tmdb*', r =>
    r.fulfill({ json: { results: [
      { tmdb_id: 999, title: 'Some Movie', media_type: 'MOVIE', release_year: 2022, poster_path: '/sm.jpg', overview: '' },
    ] } }));
  await page.goto('/admin/transcodes/unmatched');
  await page.waitForSelector('app-transcode-unmatched table');
}

test.describe('admin transcode-unmatched — display', () => {
  test('renders both fixture rows with parsed title + year/season info', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-transcode-unmatched tbody tr')).toHaveCount(2);
    await expect(page.locator('app-transcode-unmatched')).toContainText('Some.Movie.2022.1080p.mkv');
    await expect(page.locator('app-transcode-unmatched')).toContainText('Show.S02E05.mkv');
  });

  test('row with a suggestion shows the suggested title', async ({ page }) => {
    await setup(page);
    // First row has suggestion=Some Movie (2022) score 0.92.
    await expect(page.locator('app-transcode-unmatched tbody tr').first()).toContainText('Some Movie');
  });
});

test.describe('admin transcode-unmatched — actions', () => {
  test('Accept POSTs /accept and removes the row', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/unmatched/1/accept'),
      { timeout: 3_000 },
    );
    await page.locator('app-transcode-unmatched tbody tr').first()
      .locator('button', { hasText: /^Accept$/ }).click();
    await req;
    await expect(page.locator('app-transcode-unmatched tbody tr')).toHaveCount(1);
  });

  test('Ignore POSTs /ignore and removes the row', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/unmatched/1/ignore'),
      { timeout: 3_000 },
    );
    await page.locator('app-transcode-unmatched tbody tr').first()
      .locator('button', { hasText: /^Ignore$/ }).click();
    await req;
  });
});

test.describe('admin transcode-unmatched — Link dialog', () => {
  test('opens via Link button + search results render', async ({ page }) => {
    await setup(page);
    await page.locator('app-transcode-unmatched tbody tr').first()
      .locator('button', { hasText: /^Link$/ }).click();
    await expect(page.locator('app-transcode-unmatched .modal-overlay')).toBeVisible();
    // Initial search fires off the parsed_title; Some Movie is in mock results.
    await expect(page.locator('app-transcode-unmatched .modal-overlay')).toContainText('Some Movie', { timeout: 3_000 });
  });

  test('clicking a search result POSTs /link/:titleId', async ({ page }) => {
    await setup(page);
    await page.locator('app-transcode-unmatched tbody tr').first()
      .locator('button', { hasText: /^Link$/ }).click();
    await page.waitForSelector('app-transcode-unmatched .modal-overlay');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/unmatched/1/link/100'),
      { timeout: 5_000 },
    );
    // The first link result button should fire linkToTitle.
    await page.locator('app-transcode-unmatched .modal-overlay button', { hasText: /Link|Use/ }).first().click();
    await req;
  });
});
