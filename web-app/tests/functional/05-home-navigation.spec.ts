import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Home page navigation test. The home view stitches together eight
// carousels and a couple of dismiss buttons; this spec verifies that
// every clickable element routes to the right place (or, for the
// audio resume row, primes the bottom-bar player without navigating).
//
// Fixture ids referenced (from catalog/home.populated.json):
//   continue_watching       transcode_id=1, title_id=100
//   recently_added          title_id=101
//   resume_listening        track_id=4001, title_id=301
//   resume_reading          media_item_id=5001
//   recently_added_books    title_id=201
//   recently_added_albums   title_id=301
//   recently_watched        title_id=103
//   missing_seasons         title_id=401
test.describe('home navigation', () => {

  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    // Don't audit until the populated home rendered. The resume_reading
    // section is the latest one to arrive, so waiting for any carousel
    // header is enough.
    await page.waitForSelector('app-home .carousel-section');
  });

  // -------- Navigation: each carousel → expected route --------

  test('Continue Watching poster → /play/:id', async ({ page }) => {
    // The play link is the .poster-wrapper anchor; the title link
    // below it goes to /title/:id (covered separately).
    await page.locator('.carousel-section', { hasText: 'Continue Watching' })
      .locator('.poster-wrapper').first().click();
    await expect(page).toHaveURL(/\/play\/1(?:\?|$)/);
  });

  test('Continue Watching title link → /title/:id', async ({ page }) => {
    await page.locator('.carousel-section', { hasText: 'Continue Watching' })
      .locator('a.poster-title').first().click();
    await expect(page).toHaveURL(/\/title\/100$/);
  });

  test('Recently Added → /title/:id', async ({ page }) => {
    await page.locator('.carousel-section', { hasText: 'Recently Added' })
      .filter({ hasNotText: 'Books' })
      .filter({ hasNotText: 'Albums' })
      .locator('a.poster-card').first().click();
    await expect(page).toHaveURL(/\/title\/101$/);
  });

  test('Resume Reading → /reader/:mediaItemId', async ({ page }) => {
    await page.locator('.carousel-section', { hasText: 'Resume Reading' })
      .locator('a.poster-card').first().click();
    await expect(page).toHaveURL(/\/reader\/5001$/);
  });

  test('Recently Added Books → /title/:id', async ({ page }) => {
    await page.locator('.carousel-section', { hasText: 'Recently Added Books' })
      .locator('a.poster-card').first().click();
    await expect(page).toHaveURL(/\/title\/201$/);
  });

  test('Recently Added Albums → /title/:id', async ({ page }) => {
    await page.locator('.carousel-section', { hasText: 'Recently Added Albums' })
      .locator('a.poster-card').first().click();
    await expect(page).toHaveURL(/\/title\/301$/);
  });

  test('Recently Watched → /title/:id', async ({ page }) => {
    await page.locator('.carousel-section', { hasText: 'Recently Watched' })
      .locator('a.poster-card').first().click();
    await expect(page).toHaveURL(/\/title\/103$/);
  });

  test('New Seasons Available → /title/:id', async ({ page }) => {
    await page.locator('.carousel-section', { hasText: 'New Seasons Available' })
      .locator('a.poster-card').first().click();
    await expect(page).toHaveURL(/\/title\/401$/);
  });

  // -------- Continue Listening: starts playback, no navigation --------

  test('Continue Listening primes the bottom-bar player without navigating', async ({ page }) => {
    await page.locator('.carousel-section', { hasText: 'Continue Listening' })
      .locator('button.poster-card').first().click();

    // No navigation — URL stays at /
    await expect(page).toHaveURL(/\/$/);

    // Bottom-bar audio player becomes visible with the right track.
    // .audio-player gets `.visible` once queue.hasQueue() flips true,
    // and the .now-playing block renders the current track name.
    const player = page.locator('app-audio-player .audio-player');
    await expect(player).toHaveClass(/visible/, { timeout: 2_000 });
    await expect(player.locator('.now-playing .track-name')).toContainText('So What');
  });

  // -------- Dismiss buttons --------

  test('Continue Watching × removes row and fires ClearProgress', async ({ page }) => {
    // Capture the gRPC ClearProgress request to confirm dismissal
    // routed to PlaybackService. Listen alongside the click so the
    // request is in flight when we start.
    const cleared = page.waitForRequest(req =>
      req.url().endsWith('/mediamanager.PlaybackService/ClearProgress'),
    );

    const cw = page.locator('.carousel-section', { hasText: 'Continue Watching' });
    await expect(cw.locator('.poster-card')).toHaveCount(1);

    await cw.locator('.dismiss-btn').first().click();
    await cleared;

    // Section disappears entirely once continue_watching is empty
    // (the @if (feed.continue_watching.length > 0) guard hides it).
    await expect(page.locator('.carousel-section', { hasText: 'Continue Watching' })).toHaveCount(0);
  });

  test('Missing Seasons × removes row and calls DismissMissingSeason', async ({ page }) => {
    // DismissMissingSeason now lands on gRPC; mock-backend's default
    // dispatch returns Empty. Capture the request to assert the click
    // wired through.
    const dismissed = page.waitForRequest(req =>
      req.method() === 'POST'
      && req.url().endsWith('/mediamanager.CatalogService/DismissMissingSeason'),
    );

    const ms = page.locator('.carousel-section', { hasText: 'New Seasons Available' });
    await expect(ms.locator('.poster-card')).toHaveCount(1);

    // The dismiss button lives inside the .poster-wrapper anchor in
    // missing-seasons (different from continue-watching where it's a
    // sibling). Stop the click from also navigating to /title/401.
    await ms.locator('.dismiss-btn').first().click();
    await dismissed;

    await expect(page.locator('.carousel-section', { hasText: 'New Seasons Available' })).toHaveCount(0);
    // And the navigation did NOT happen (handler called preventDefault).
    await expect(page).toHaveURL(/\/$/);
  });
});
