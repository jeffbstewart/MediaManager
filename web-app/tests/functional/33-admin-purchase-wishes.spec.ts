import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// /admin/purchase-wishes — PurchaseWishesComponent. Aggregated user
// wishes (media + album) with lifecycle filter chips + per-row
// status pill (mat-menu trigger for media; read-only for albums).

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/admin/purchase-wishes', r =>
    r.fulfill({ json: { wishes: [
      {
        wish_type: 'MEDIA', tmdb_id: 245891, title: 'John Wick',
        display_title: 'John Wick', media_type: 'movie',
        poster_path: '/jw.jpg', release_year: 2014, season_number: null,
        vote_count: 3, voters: ['alice', 'bob', 'carol'],
        lifecycle_stage: 'WISHED_FOR', lifecycle_label: 'Wished for',
      },
      {
        wish_type: 'MEDIA', tmdb_id: 1399, title: 'Game of Thrones',
        display_title: 'Game of Thrones — Season 4', media_type: 'TV',
        poster_path: '/got.jpg', release_year: 2011, season_number: 4,
        vote_count: 1, voters: ['dana'],
        lifecycle_stage: 'READY_TO_WATCH', lifecycle_label: 'Ready to watch',
      },
      {
        wish_type: 'ALBUM', release_group_id: 'rg-1',
        title: 'Kind of Blue', display_title: 'Kind of Blue — Miles Davis',
        primary_artist: 'Miles Davis', is_compilation: false,
        release_year: 1959, cover_release_id: 'r-1',
        vote_count: 2, voters: ['ed', 'fran'],
        lifecycle_stage: 'WISHED_FOR', lifecycle_label: 'Wished for',
      },
    ], total: 3 } }));
  await page.route('**/api/v2/admin/purchase-wishes/set-status', r =>
    r.fulfill({ json: { ok: true } }));
  await page.goto('/admin/purchase-wishes');
  await page.waitForSelector('app-purchase-wishes table');
}

test.describe('admin purchase-wishes — display', () => {
  test('renders one row per fixture wish (media + album mixed)', async ({ page }) => {
    await setup(page);
    // Filter defaults hide READY_TO_WATCH + NOT_FEASIBLE; GoT (READY_TO_WATCH)
    // is filtered out of the visible rows.
    const rows = page.locator('app-purchase-wishes tbody tr');
    await expect(rows).toHaveCount(2);
    await expect(rows).toContainText(['John Wick', 'Kind of Blue']);
  });

  test('Type column shows Movie / TV / CD', async ({ page }) => {
    await setup(page);
    // Toggle READY_TO_WATCH on so all three rows are visible.
    await page.locator('app-purchase-wishes mat-chip', { hasText: 'Ready to watch' }).click();
    const rows = page.locator('app-purchase-wishes tbody tr');
    await expect(rows.locator('text=/^Movie$/')).toHaveCount(1);
    await expect(rows.locator('text=/^TV$/')).toHaveCount(1);
    await expect(rows.locator('text=/^CD$/')).toHaveCount(1);
  });

  test('voters column joins reporter names', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-purchase-wishes tr', { hasText: 'John Wick' }))
      .toContainText('alice, bob, carol');
  });

  test('vote count badge renders the right number', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-purchase-wishes tr', { hasText: 'John Wick' })
      .locator('.vote-badge')).toContainText('3');
  });
});

test.describe('admin purchase-wishes — filter chips', () => {
  test('READY_TO_WATCH and NOT_FEASIBLE start unchecked', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-purchase-wishes mat-chip', { hasText: 'Ready to watch' }))
      .not.toHaveClass(/mat-mdc-chip-highlighted/);
    await expect(page.locator('app-purchase-wishes mat-chip', { hasText: 'Not feasible' }))
      .not.toHaveClass(/mat-mdc-chip-highlighted/);
    // Wished for starts on.
    await expect(page.locator('app-purchase-wishes mat-chip', { hasText: 'Wished for' }))
      .toHaveClass(/mat-mdc-chip-highlighted/);
  });

  test('toggling a chip filters rows in/out without re-fetching', async ({ page }) => {
    await setup(page);
    // Toggle off Wished-for; both wished-for rows should disappear.
    await page.locator('app-purchase-wishes mat-chip', { hasText: 'Wished for' }).click();
    await expect(page.locator('app-purchase-wishes tbody tr')).toHaveCount(0);
    await expect(page.locator('app-purchase-wishes')).toContainText('No wishes match the active filters');
  });
});

test.describe('admin purchase-wishes — status update', () => {
  test('clicking the status pill on a media row opens menu', async ({ page }) => {
    await setup(page);
    await page.locator('app-purchase-wishes tr', { hasText: 'John Wick' })
      .locator('button.status-badge').click();
    await expect(page.locator('.mat-mdc-menu-panel button', { hasText: 'Ordered' })).toBeVisible();
  });

  test('selecting Ordered POSTs /set-status', async ({ page }) => {
    await setup(page);
    await page.locator('app-purchase-wishes tr', { hasText: 'John Wick' })
      .locator('button.status-badge').click();
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/purchase-wishes/set-status'),
      { timeout: 3_000 },
    );
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Ordered' }).click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({
      tmdb_id: 245891, media_type: 'movie', season_number: null, status: 'ORDERED',
    });
  });

  test('album row\'s status pill is read-only (not a button)', async ({ page }) => {
    await setup(page);
    const albumRow = page.locator('app-purchase-wishes tr', { hasText: 'Kind of Blue' });
    await expect(albumRow.locator('span.status-badge.readonly')).toBeVisible();
    await expect(albumRow.locator('button.status-badge')).toHaveCount(0);
  });
});
