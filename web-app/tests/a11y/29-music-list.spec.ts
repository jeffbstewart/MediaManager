import { test, expect } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Music list view tests.
//
// /content/music mounts <app-music>, an artist-grid built on
// /api/v2/catalog/artists. Layout differs from the title-grid used
// by movies/books: each card is an artist tile (headshot or fallback
// album cover, name, "<n> albums"), and the only filter chip is
// Playable plus a typed search input. Sort options are
// Albums (default), Name, Recent.
//
// Fixture: catalog/artists.list.json — 4 artists (Miles Davis +
// headshot, Radiohead + headshot, Björk no headshot but fallback
// poster, Lonesome Crew with neither image source).

test.describe('music list view', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/content/music');
    await page.waitForSelector('app-music .artist-grid');
  });

  // -------- Display --------

  test('renders all 4 artists with name + album count', async ({ page }) => {
    const cards = page.locator('app-music .artist-card');
    await expect(cards).toHaveCount(4);

    await expect(cards.first().locator('.artist-name')).toContainText('Miles Davis');
    await expect(cards.first().locator('.artist-meta')).toContainText('8 albums');

    // Pluralization: 1 album (no "s") for Lonesome Crew.
    await expect(cards.nth(3).locator('.artist-name')).toContainText('Lonesome Crew');
    await expect(cards.nth(3).locator('.artist-meta')).toContainText('1 album');
    await expect(cards.nth(3).locator('.artist-meta')).not.toContainText('1 albums');
  });

  test('total label reflects artist count', async ({ page }) => {
    await expect(page.locator('app-music .status-label')).toContainText('4 artists');
  });

  test('hero image uses headshot_url when present', async ({ page }) => {
    const milesImg = page.locator('app-music .artist-card').first().locator('img');
    await expect(milesImg).toBeVisible();
    await expect(milesImg).toHaveAttribute('src', /\/artist-headshots\/1$/);
  });

  test('hero image falls back to album poster when headshot is null', async ({ page }) => {
    // Björk has no headshot but does have fallback_poster_url.
    const bjorkImg = page.locator('app-music .artist-card').nth(2).locator('img');
    await expect(bjorkImg).toBeVisible();
    await expect(bjorkImg).toHaveAttribute('src', /\/posters\/w185\/501$/);
  });

  test('placeholder shows when both image sources are null', async ({ page }) => {
    // Lonesome Crew has neither — placeholder div with icon.
    const last = page.locator('app-music .artist-card').nth(3);
    await expect(last.locator('img')).toHaveCount(0);
    await expect(last.locator('.artist-placeholder mat-icon')).toBeVisible();
    // GROUP type → "groups" icon (Person → "person").
    await expect(last.locator('.artist-placeholder mat-icon')).toContainText('groups');
  });

  test('clicking an artist card navigates to /artist/:id', async ({ page }) => {
    await page.locator('app-music .artist-card').first().click();
    await expect(page).toHaveURL(/\/artist\/1$/);
  });

  // -------- Filter chips --------

  test('Playable chip starts highlighted (default true) — toggling off adds playable_only=false', async ({ page }) => {
    const playable = page.locator('app-music mat-chip', { hasText: 'Playable' });
    await expect(playable).toHaveClass(/mat-mdc-chip-highlighted/);

    const reqPromise = page.waitForRequest(req =>
      req.url().includes('/api/v2/catalog/artists')
        && req.url().includes('playable_only=false'),
      { timeout: 3_000 },
    );
    await playable.click();
    await reqPromise;
    await expect(playable).not.toHaveClass(/mat-mdc-chip-highlighted/);
  });

  test('selected sort chip is highlighted (Albums by default)', async ({ page }) => {
    const albums = page.locator('app-music mat-chip', { hasText: 'Albums' });
    await expect(albums).toHaveClass(/mat-mdc-chip-highlighted/);

    await page.locator('app-music mat-chip', { hasText: 'Name' }).click();
    await expect(page.locator('app-music mat-chip', { hasText: 'Name' }))
      .toHaveClass(/mat-mdc-chip-highlighted/);
    await expect(albums).not.toHaveClass(/mat-mdc-chip-highlighted/);
  });

  test('clicking each sort chip fires a request with the right sort=<mode>', async ({ page }) => {
    // Albums is the default; iterate through the other two and back.
    for (const { label, param } of [
      { label: 'Name',   param: 'sort=name' },
      { label: 'Recent', param: 'sort=recent' },
      { label: 'Albums', param: 'sort=albums' },
    ]) {
      const reqPromise = page.waitForRequest(req =>
        req.url().includes('/api/v2/catalog/artists')
          && req.url().includes(param),
        { timeout: 3_000 },
      );
      await page.locator('app-music mat-chip', { hasText: label }).click();
      await reqPromise;
    }
  });

  // -------- Search --------

  test('typing in the search box fires a debounced request with q=<text>', async ({ page }) => {
    // 200 ms debounce in the component — wait for the request to
    // land. Using fill() rather than per-keystroke type() because
    // the test isn't asserting on intermediate keystroke debounce
    // behaviour, just that the eventual request carries q=miles.
    const reqPromise = page.waitForRequest(req =>
      req.url().includes('/api/v2/catalog/artists')
        && /q=miles/i.test(req.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-music input.search-input').fill('miles');
    await reqPromise;
  });

  test('clear button empties the search and fires a request without q', async ({ page }) => {
    // Type something first.
    let typed = page.waitForRequest(req =>
      req.url().includes('/api/v2/catalog/artists') && /q=miles/.test(req.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-music input.search-input').fill('miles');
    await typed;

    // Then click the clear (×) button. Component drops q from the
    // request entirely.
    const cleared = page.waitForRequest(req => {
      if (!req.url().includes('/api/v2/catalog/artists')) return false;
      return !new URL(req.url()).searchParams.has('q');
    }, { timeout: 3_000 });
    await page.locator('app-music button.search-clear').click();
    await cleared;
    await expect(page.locator('app-music input.search-input')).toHaveValue('');
  });
});
