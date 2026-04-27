import { test, expect, Page, Route } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { fromBinary } from '@bufbuild/protobuf';
import { SetTitleTagsRequestSchema } from '../../src/app/proto-gen/common_pb';

// Title detail page integration test. Exercises every interactive
// element on a movie page, every TV-specific surface, and the
// admin-only widgets (Edit DVD, tag add/remove).
//
// Fixture ids (from catalog/title.{movie,tv}.json):
//   movie 100 — The Matrix; cast 1/2/3; similar 102/103;
//               admin items 5001 (BLURAY) + 5002 (UHD_BLURAY).
//   tv 200 — Breaking Bad; 2 cast, 3 episodes across seasons 1+2,
//            similar 100; admin item 6001.

const MOVIE_ID = 100;
const TV_ID = 200;

/**
 * SetFavorite, SetHidden, and SetTitleTags are no-op'd by mock-backend's
 * default gRPC dispatch (they just need to resolve so the title-detail
 * handler's awaited promise unblocks and local-state update runs).
 * Tests that need to capture the request register their own override
 * before the helper runs.
 */
async function stubWriteEndpoints(_page: Page, _titleId: number) {
  // No-op — left as a hook in case a future endpoint needs targeted
  // stubbing; the parameters are retained so callers don't churn.
}

test.describe('title detail — movie (viewer)', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
    await stubWriteEndpoints(page, MOVIE_ID);
    await page.goto(`/title/${MOVIE_ID}`);
    await page.waitForSelector('app-title-detail .detail-container');
  });

  test('hero poster + backdrop + summary all render', async ({ page }) => {
    await expect(page.locator('img.hero-poster')).toBeVisible();
    await expect(page.locator('img.hero-backdrop')).toBeVisible();
    await expect(page.locator('img.hero-poster')).toHaveAttribute('src', /\/posters\/w500\/100$/);
    await expect(page.locator('img.hero-backdrop')).toHaveAttribute('src', /\/backdrops\/100$/);
    await expect(page.locator('.description')).toContainText('computer hacker learns');
  });

  test('tag chip shows the existing tag', async ({ page }) => {
    const chip = page.locator('.tag-row a.tag-badge');
    await expect(chip).toContainText('Comfort Watch');
    await expect(chip).toHaveAttribute('href', /\/tag\/1$/);
  });

  test('admin-only controls are hidden for a viewer', async ({ page }) => {
    // Admin items row, tag-add button, and tag-remove × should all
    // be absent. The tag chip itself remains visible (it's read-only
    // for non-admins).
    await expect(page.locator('.admin-items-row')).toHaveCount(0);
    await expect(page.locator('.tag-add-btn')).toHaveCount(0);
    await expect(page.locator('.tag-remove-btn')).toHaveCount(0);
  });

  test('star button posts SetFavorite and flips the starred class', async ({ page }) => {
    const starBtn = page.locator('button.star-btn');
    await expect(starBtn).not.toHaveClass(/starred/);
    const posted = page.waitForRequest(req =>
      req.method() === 'POST'
      && req.url().endsWith('/mediamanager.CatalogService/SetFavorite'),
    );
    await starBtn.click();
    await posted;
    await expect(starBtn).toHaveClass(/starred/);
  });

  test('hide button confirms and posts SetHidden', async ({ page }) => {
    // confirm() is a native dialog; intercept and accept it.
    page.on('dialog', d => d.accept());
    const posted = page.waitForRequest(req =>
      req.method() === 'POST'
      && req.url().endsWith('/mediamanager.CatalogService/SetHidden'),
    );
    await page.locator('button.hide-btn').click();
    await posted;
    await expect(page.locator('button.hide-btn')).toHaveClass(/hidden-title/);
  });

  test('transcode play link navigates to /play/:transcodeId', async ({ page }) => {
    // No saved progress on this transcode (no position_seconds in
    // the fixture), so the big "Resume" button doesn't render. The
    // play affordance is the .tc-play-link icon in the transcode
    // row — same routerLink target.
    await page.locator('a.tc-play-link').first().click();
    await expect(page).toHaveURL(/\/play\/1001(?:\?|$)/);
  });

  test('cast cards show headshot + link to /actor/:tmdbPersonId', async ({ page }) => {
    const cast = page.locator('.cast-row a.cast-card');
    await expect(cast).toHaveCount(3);

    // First cast card: Keanu Reeves, tmdb_person_id 6384.
    const first = cast.first();
    await expect(first).toContainText('Keanu Reeves');
    await expect(first.locator('img.cast-img')).toBeVisible();
    await expect(first).toHaveAttribute('href', /\/actor\/6384$/);
  });

  test('similar titles show poster + link to /title/:id', async ({ page }) => {
    const similar = page.locator('.similar-row a.poster-card');
    await expect(similar).toHaveCount(2);
    const first = similar.first();
    await expect(first).toContainText('Interstellar');
    await expect(first.locator('img.poster-img')).toBeVisible();
    await expect(first).toHaveAttribute('href', /\/title\/102$/);
  });
});

test.describe('title detail — movie (admin)', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await stubWriteEndpoints(page, MOVIE_ID);
    await page.goto(`/title/${MOVIE_ID}`);
    await page.waitForSelector('app-title-detail .detail-container');
  });

  test('Edit DVD links surface for both admin media items', async ({ page }) => {
    const items = page.locator('.admin-items-row a.admin-item-link');
    await expect(items).toHaveCount(2);
    await expect(items.first()).toContainText('Edit Blu-ray');
    await expect(items.first()).toContainText('888574293321');
    await expect(items.first()).toHaveAttribute('href', /\/admin\/item\/5001$/);
    await expect(items.nth(1)).toContainText('4K UHD');
    await expect(items.nth(1)).toHaveAttribute('href', /\/admin\/item\/5002$/);
  });

  test('Tag-add button opens the tag picker', async ({ page }) => {
    await page.locator('button.tag-add-btn').click();
    await expect(page.locator('app-tag-picker')).toBeVisible({ timeout: 2_000 });
  });

  test('Tag-remove × posts setTitleTags with the tag dropped', async ({ page }) => {
    // setTitleTags now lands on gRPC. The request body is a
    // gRPC-Web framed binary SetTitleTagsRequest, not JSON, so we
    // unframe + decode before asserting.
    const posted = page.waitForRequest(req =>
      req.method() === 'POST'
      && req.url().endsWith('/mediamanager.CatalogService/SetTitleTags'),
    );
    await page.locator('button.tag-remove-btn').first().click();
    const req = await posted;
    const decoded = fromBinary(
      SetTitleTagsRequestSchema,
      unframeGrpcWebRequest(req.postDataBuffer()),
    );
    expect(decoded.titleId).toBe(BigInt(MOVIE_ID));
    expect(decoded.tagIds).toEqual([]);
  });
});

test.describe('title detail — TV (viewer)', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
    await stubWriteEndpoints(page, TV_ID);
    await page.goto(`/title/${TV_ID}`);
    await page.waitForSelector('app-title-detail .detail-container');
  });

  test('hero, summary, and seasons section all render', async ({ page }) => {
    await expect(page.locator('img.hero-poster')).toBeVisible();
    await expect(page.locator('img.hero-backdrop')).toBeVisible();
    await expect(page.locator('.description')).toContainText('chemistry teacher');
    // Seasons chips section
    const seasons = page.locator('.season-chips .season-chip');
    await expect(seasons).toHaveCount(2);
    await expect(seasons.first()).toContainText('S1');
  });

  test('starred state reflects fixture (is_starred=true)', async ({ page }) => {
    await expect(page.locator('button.star-btn')).toHaveClass(/starred/);
  });

  test('season selector buttons filter the episode list', async ({ page }) => {
    // Default season is the lowest with content (S1). Pilot + Cat's
    // in the Bag are visible; Seven Thirty-Seven is not.
    const list = page.locator('.episode-list');
    await expect(list.locator('.episode-row')).toHaveCount(2);
    await expect(list).toContainText('Pilot');

    // Click S2 selector. Filtered list flips to the single S2 episode.
    await page.locator('.season-selector button', { hasText: /Season 2|^S2/ }).click();
    await expect(list.locator('.episode-row')).toHaveCount(1);
    await expect(list).toContainText('Seven Thirty-Seven');
  });

  test('episode play link navigates to /play/:transcodeId', async ({ page }) => {
    // First episode (Pilot) has transcode_id 2001.
    await page.locator('.episode-list .episode-row').first().locator('a.ep-play-link').click();
    await expect(page).toHaveURL(/\/play\/2001(?:\?|$)/);
  });

  test('cast cards work the same as on movies', async ({ page }) => {
    const cast = page.locator('.cast-row a.cast-card');
    await expect(cast).toHaveCount(2);
    await expect(cast.first()).toContainText('Bryan Cranston');
    await expect(cast.first().locator('img.cast-img')).toBeVisible();
    await expect(cast.first()).toHaveAttribute('href', /\/actor\/17419$/);
  });

  test('similar titles link cross-type back to a movie', async ({ page }) => {
    const similar = page.locator('.similar-row a.poster-card');
    await expect(similar).toHaveCount(1);
    await expect(similar.first()).toContainText('The Matrix');
    await expect(similar.first()).toHaveAttribute('href', /\/title\/100$/);
  });
});
