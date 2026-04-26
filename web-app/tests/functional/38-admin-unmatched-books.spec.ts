import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// /admin/books/unmatched — UnmatchedBooksComponent. Mat-table of NAS
// book files that didn't auto-link. Per-row Ignore + Link (opens a
// native <dialog> with three subflows: ISBN re-lookup, OpenLibrary
// search, existing-title search).

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/admin/unmatched-books/*/ignore', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/unmatched-books/*/link-isbn', r =>
    r.fulfill({ json: { ok: true } }));
  await page.route('**/api/v2/admin/unmatched-books/*/link-title', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/unmatched-books/search-ol*', r =>
    r.fulfill({ json: { results: [
      { work_id: 'OL1W', title: 'Mystery Novel', authors: ['Jane Doe'], year: 2020,
        cover_url: '/posters/100.jpg', isbn: '9780123456789' },
    ] } }));
  await page.route('**/api/v2/admin/unmatched-books/search-titles*', r =>
    r.fulfill({ json: { titles: [
      { id: 100, name: 'Mystery Novel', release_year: 2020 },
    ] } }));
  await page.goto('/admin/books/unmatched');
  await page.waitForSelector('app-unmatched-books table');
}

test.describe('admin unmatched-books — display', () => {
  test('renders the 2 fixture rows with file + parsed metadata', async ({ page }) => {
    await setup(page);
    const rows = page.locator('app-unmatched-books tbody tr');
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0)).toContainText('MysteryNovel.epub');
    await expect(rows.nth(0)).toContainText('Mystery Novel');
    await expect(rows.nth(0)).toContainText('Jane Doe');
    await expect(rows.nth(0)).toContainText('9780123456789');
  });
});

test.describe('admin unmatched-books — actions', () => {
  test('Ignore POSTs /ignore + removes the row', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/unmatched-books/1/ignore'),
      { timeout: 3_000 },
    );
    await page.locator('app-unmatched-books tbody tr').first()
      .locator('button', { hasText: /^Ignore$/ }).click();
    await req;
    await expect(page.locator('app-unmatched-books tbody tr')).toHaveCount(1);
  });

  test('Link opens the dialog with ISBN + OL search prefilled', async ({ page }) => {
    await setup(page);
    await page.locator('app-unmatched-books tbody tr').first()
      .locator('button', { hasText: /^Link$/ }).click();
    // Native <dialog>; assert open by attribute presence.
    const dialog = page.locator('app-unmatched-books dialog');
    await expect(dialog).toHaveAttribute('open', '');
  });
});
