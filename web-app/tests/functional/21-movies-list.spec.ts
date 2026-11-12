import { test, expect, Page, Request } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { fromBinary } from '@bufbuild/protobuf';
import { ListTitlesRequestSchema } from '../../src/app/proto-gen/catalog_pb';
import { expectView } from '../helpers/expect-view';

const LIST_TITLES_URL = '/mediamanager.CatalogService/ListTitles';

function decodeListTitles(req: Request): { sort: string; ratings: string[]; playableOnly: boolean } {
  const decoded = fromBinary(
    ListTitlesRequestSchema,
    unframeGrpcWebRequest(req.postDataBuffer()),
  );
  return { sort: decoded.sort, ratings: decoded.ratings, playableOnly: decoded.playableOnly };
}

// Movies list view tests.
//
// /content/movies mounts <app-title-grid mediaType="MOVIE">. The
// title-grid filter wiring is shared with /content/books, /content/tv,
// /content/music and /content/family — the books spec
// (15-books-and-reader) already covers the generic chip / sort / URL-
// param plumbing. This spec adds the movies-specific bits:
//   - Popular sort option (replaces Author from books)
//   - Rating chips (G, PG, PG-13, R) which only appear for video
//     mediaTypes
//
// Actual movie playback isn't re-covered here. The /play/:transcodeId
// route is media-type agnostic; 13-player-playback exercises the full
// HTMLMediaElement pipeline (currentTime, subs, scrub preview) against
// a generic video transcode and 14-player-resume covers the resume
// prompt. Both apply to movies as much as TV episodes.
//
// Fixture: catalog/titles.movies.json — 4 movies (Matrix, Inception,
// Interstellar, Blade Runner 2049). available_ratings: G, PG, PG-13,
// R. Inception has progress_fraction=0.34. Blade Runner 2049 has
// no poster + isn't playable.

function captureTitlesRequests(page: Page) {
  const urls: string[] = [];
  page.on('request', req => {
    if (req.url().endsWith(LIST_TITLES_URL)) urls.push(req.url());
  });
  return () => urls;
}

test.describe('movies list view', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/content/movies');
    await page.waitForSelector('app-title-grid .poster-card');
  });

  // -------- Display --------

  test('renders all 4 movies with title + release year', async ({ page }) => {
    const cards = page.locator('app-title-grid .poster-card');
    await expect(cards).toHaveCount(4);
    await expect(cards.first().locator('.poster-title')).toContainText('The Matrix');
    await expect(cards.first().locator('.poster-meta')).toContainText('1999');
    await expect(cards.nth(1).locator('.poster-title')).toContainText('Inception');
    await expect(cards.nth(1).locator('.poster-meta')).toContainText('2010');
    await expect(cards.nth(2).locator('.poster-title')).toContainText('Interstellar');
    await expect(cards.nth(3).locator('.poster-title')).toContainText('Blade Runner 2049');
  });

  test('total label reflects movie count', async ({ page }) => {
    await expect(page.locator('app-title-grid .status-label')).toContainText('4 movies');
  });

  test('hero image renders when poster_url is set, placeholder otherwise', async ({ page }) => {
    const cards = page.locator('app-title-grid .poster-card');
    // Matrix has poster_url
    await expect(cards.first().locator('img.poster-img')).toBeVisible();
    await expect(cards.first().locator('img.poster-img'))
      .toHaveAttribute('src', /\/posters\/w185\/100$/);
    // Blade Runner 2049 has poster_url=null → placeholder div, no <img>
    await expect(cards.nth(3).locator('img.poster-img')).toHaveCount(0);
    await expect(cards.nth(3).locator('.poster-placeholder')).toBeVisible();
  });

  test('playable badge shows only on playable titles', async ({ page }) => {
    const cards = page.locator('app-title-grid .poster-card');
    // Matrix, Inception, Interstellar are playable.
    await expect(cards.first().locator('.playable-badge')).toBeVisible();
    await expect(cards.nth(1).locator('.playable-badge')).toBeVisible();
    await expect(cards.nth(2).locator('.playable-badge')).toBeVisible();
    // Blade Runner 2049 is not.
    await expect(cards.nth(3).locator('.playable-badge')).toHaveCount(0);
  });

  test('progress overlay renders on a partly-watched movie', async ({ page }) => {
    // Inception has progress_fraction=0.34.
    await expect(page.locator('app-title-grid .poster-card').nth(1).locator('.progress-fill'))
      .toHaveAttribute('style', /width:\s*34%/);
  });

  // -------- Layout: the spec .grid --------

  test('grid uses 192px minimum columns, a 32px gap and 2:3 art at desktop', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const grid = page.locator('app-title-grid .grid');
    await expect(grid).toHaveCSS('column-gap', '32px');
    const art = page.locator('app-title-grid .poster-card').first().locator('.art');
    const box = await art.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.width).toBeGreaterThanOrEqual(192);
    expect(box!.height / box!.width).toBeCloseTo(1.5, 1);
    // The card fills its grid column.
    const card = await page.locator('app-title-grid .poster-card').first().boundingBox();
    const cols = await grid.evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').length);
    const gridBox = await grid.boundingBox();
    expect(card!.width).toBeCloseTo((gridBox!.width - 32 * (cols - 1)) / cols, 0);
  });

  test('grid steps down to 104px columns and a 12px gap at phone width', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const grid = page.locator('app-title-grid .grid');
    await expect(grid).toHaveCSS('column-gap', '12px');
    const cols = await grid.evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').length);
    expect(cols).toBe(3);
    const box = await page.locator('app-title-grid .poster-card').first().boundingBox();
    expect(box!.width).toBeGreaterThanOrEqual(104);
  });

  test('clicking a movie navigates to /title/:id', async ({ page }) => {
    await page.locator('app-title-grid .poster-card').first().click();
    await expectView(page, /\/title\/100$/, 'app-title-detail');
  });

  // -------- Sort chips: movies-specific Popular option --------

  test('sort chips include the movies-specific Popular option', async ({ page }) => {
    // Books would show "Author" instead of "Popular". Movies show
    // Name, Year, Recent, Popular.
    for (const label of ['Name', 'Year', 'Recent', 'Popular']) {
      await expect(page.locator('.filter-chip', { hasText: label })).toBeVisible();
    }
    // And explicitly NOT "Author".
    await expect(page.locator('.filter-chip', { hasText: /^\s*Author\s*$/ })).toHaveCount(0);
  });

  test('clicking each sort chip fires a ListTitles request with the right sort', async ({ page }) => {
    // ListTitles is a gRPC POST; the sort field rides in the binary
    // request body, so we decode the proto to assert on it.
    for (const { label, sort } of [
      { label: 'Year',    sort: 'year' },
      { label: 'Recent',  sort: 'recent' },
      { label: 'Popular', sort: 'popular' },
      { label: 'Name',    sort: 'name' },
    ]) {
      const reqPromise = page.waitForRequest(req =>
        req.url().endsWith(LIST_TITLES_URL),
        { timeout: 3_000 },
      );
      await page.locator('.filter-chip', { hasText: label }).click();
      const got = await reqPromise;
      expect(decodeListTitles(got).sort).toBe(sort);
    }
  });

  test('filter and sort chips are 40px pills in one scrolling row', async ({ page }) => {
    const chips = page.locator('.filter-chips .filter-chip');
    await expect(chips.first()).toBeVisible();
    const count = await chips.count();
    expect(count).toBeGreaterThan(5);
    const tops = new Set<number>();
    for (let i = 0; i < count; i++) {
      const box = await chips.nth(i).boundingBox();
      expect(box).not.toBeNull();
      expect(Math.round(box!.height)).toBe(40);
      tops.add(Math.round(box!.y));
    }
    // One row: the chips never wrap; the row scrolls sideways instead.
    expect(tops.size).toBe(1);
    await expect(page.locator('.filter-chips')).toHaveCSS('overflow-x', 'auto');
    // Exactly one chip per group is pressed by default: Playable,
    // All Ratings, and the Name sort.
    await expect(page.locator('.filter-chip[aria-pressed="true"]')).toHaveCount(3);
  });

  // -------- Filter chips: Playable + ratings --------

  test('Playable chip starts highlighted (default true) — toggling off sends playableOnly=false', async ({ page }) => {
    const playable = page.locator('.filter-chip', { hasText: 'Playable' }).first();
    await expect(playable).toHaveAttribute('aria-pressed', 'true');

    const reqPromise = page.waitForRequest(req =>
      req.url().endsWith(LIST_TITLES_URL),
      { timeout: 3_000 },
    );
    await playable.click();
    const got = await reqPromise;
    expect(decodeListTitles(got).playableOnly).toBe(false);
    await expect(playable).toHaveAttribute('aria-pressed', 'false');
  });

  test('rating chips render from available_ratings + All Ratings is selected by default', async ({ page }) => {
    // available_ratings is ["G", "PG", "PG-13", "R"]; "All Ratings"
    // is the synthetic clear-all chip. Use anchored regex / getByText
    // exact-match so "G" doesn't match "PG" / "PG-13" by substring.
    await expect(page.locator('.filter-chip', { hasText: 'All Ratings' })).toBeVisible();
    await expect(page.getByText('G', { exact: true })).toBeVisible();
    await expect(page.getByText('PG', { exact: true })).toBeVisible();
    await expect(page.getByText('PG-13', { exact: true })).toBeVisible();
    await expect(page.getByText('R', { exact: true })).toBeVisible();
    // No selectedRatings → All Ratings is highlighted.
    await expect(page.locator('.filter-chip', { hasText: 'All Ratings' }))
      .toHaveAttribute('aria-pressed', 'true');
  });

  test('clicking a rating chip sends ratings=[rating] in the request', async ({ page }) => {
    const reqPromise = page.waitForRequest(req =>
      req.url().endsWith(LIST_TITLES_URL),
      { timeout: 3_000 },
    );
    await page.locator('.filter-chip', { hasText: 'PG-13' }).click();
    const got = await reqPromise;
    expect(decodeListTitles(got).ratings).toEqual(['PG-13']);
    // PG-13 chip is now highlighted; All Ratings is not.
    await expect(page.locator('.filter-chip', { hasText: 'PG-13' }))
      .toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.filter-chip', { hasText: 'All Ratings' }))
      .toHaveAttribute('aria-pressed', 'false');
  });

  test('All Ratings clears the rating filter', async ({ page }) => {
    // First select PG-13 so there's something to clear.
    let pending = page.waitForRequest(req =>
      req.url().endsWith(LIST_TITLES_URL),
      { timeout: 3_000 },
    );
    await page.locator('.filter-chip', { hasText: 'PG-13' }).click();
    await pending;

    // Click All Ratings — request should ship an empty ratings list.
    const cleared = page.waitForRequest(req =>
      req.url().endsWith(LIST_TITLES_URL),
      { timeout: 3_000 },
    );
    await page.locator('.filter-chip', { hasText: 'All Ratings' }).click();
    const got = await cleared;
    expect(decodeListTitles(got).ratings).toEqual([]);
    await expect(page.locator('.filter-chip', { hasText: 'All Ratings' }))
      .toHaveAttribute('aria-pressed', 'true');
  });
});
