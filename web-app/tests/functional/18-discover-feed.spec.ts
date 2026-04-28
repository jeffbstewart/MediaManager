import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { fromBinary } from '@bufbuild/protobuf';
import {
  DismissArtistRecommendationRequestSchema,
} from '../../src/app/proto-gen/artist_pb';
import { AddAlbumWishRequestSchema } from '../../src/app/proto-gen/wishlist_pb';

// Discover feed (M8) tests. The page reads
// ArtistService.ListArtistRecommendations, renders one card per
// recommended artist, and exposes three actions per card / page:
//   - Wishlist (WishListService.AddAlbumWish)
//   - Dismiss  (ArtistService.DismissArtistRecommendation)
//   - Refresh  (ArtistService.RefreshArtistRecommendations, then re-load)
//
// The page is gated on has_music_radio. mockBackend's default viewer
// features fixture has it on, so no override needed unless we want
// to test the gated-off explainer state (out of scope here).

const AS = '/mediamanager.ArtistService';

test.describe('discover feed', () => {

  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    // mock-backend's AddAlbumWish handler default returns id=99.
    // Warm up FeatureService before mounting the discover component.
    // discover.ngOnInit reads features.hasMusicRadio() synchronously
    // and bails to the "needs Last.fm" empty state if the flag is
    // false. On a direct page.goto('/discover') the shell's async
    // getFeatures() race-loses to the discover mount, so we land on
    // /  first (features populate via shell + home), then in-app
    // navigate to /discover with FeatureService already hydrated.
    await page.goto('/');
    await page.waitForSelector('mat-sidenav a[href="/discover"]');
    await page.locator('mat-sidenav a[href="/discover"]').click();
    await page.waitForSelector('app-discover .card-grid');
  });

  test('renders one card per recommended artist with name + album hint', async ({ page }) => {
    const cards = page.locator('app-discover .rec-card');
    await expect(cards).toHaveCount(2);

    await expect(cards.first().locator('.artist-name')).toContainText('Bill Evans');
    await expect(cards.first().locator('.album-hint')).toContainText(
      'Start with Sunday at the Village Vanguard',
    );

    await expect(cards.nth(1).locator('.artist-name')).toContainText('John Coltrane');
    await expect(cards.nth(1).locator('.album-hint')).toContainText(
      'Start with A Love Supreme',
    );
  });

  test('voter lines render with names from the fixture', async ({ page }) => {
    // Single voter → "because you have NAME"
    await expect(page.locator('app-discover .rec-card').first().locator('.voter-line'))
      .toContainText('because you have Miles Davis');
    // Three voters → comma-and formatting via voterLine()
    await expect(page.locator('app-discover .rec-card').nth(1).locator('.voter-line'))
      .toContainText('because you have Miles Davis, Bill Evans, and Thelonious Monk');
  });

  test('cover image shows when representative_release_group_id is set', async ({ page }) => {
    // SPA constructs the cover URL same-origin from the rgid:
    // /proxy/caa/release-group/{rgid}/front-250.
    const cover = page.locator('app-discover .rec-card').first().locator('img.cover');
    await expect(cover).toBeVisible();
    await expect(cover).toHaveAttribute('src', /\/proxy\/caa\/release-group\/rg-1\/front-250$/);
  });

  test('Wishlist click fires AddAlbumWish and flips the button', async ({ page }) => {
    const card = page.locator('app-discover .rec-card').first();
    const wishBtn = card.locator('button.wish-btn');
    await expect(wishBtn).toContainText('Wishlist');

    const posted = page.waitForRequest(req =>
      req.url().endsWith('/mediamanager.WishListService/AddAlbumWish'),
    );
    await wishBtn.click();
    const req = await posted;
    const decoded = fromBinary(
      AddAlbumWishRequestSchema,
      unframeGrpcWebRequest(req.postDataBuffer()),
    );
    expect(decoded.releaseGroupId).toBe('rg-1');
    expect(decoded.title).toBe('Sunday at the Village Vanguard');
    expect(decoded.primaryArtist).toBe('Bill Evans');

    // Label flips to "Wishlisted" + the button becomes disabled so
    // double-click can't double-add.
    await expect(wishBtn).toContainText('Wishlisted');
    await expect(wishBtn).toBeDisabled();
  });

  test('Dismiss click fires DismissArtistRecommendation and removes the card', async ({ page }) => {
    const cards = page.locator('app-discover .rec-card');
    await expect(cards).toHaveCount(2);

    const posted = page.waitForRequest(req =>
      req.url().endsWith(`${AS}/DismissArtistRecommendation`),
    );
    // Dismiss the FIRST card (Bill Evans, mbid abc-1).
    await cards.first().locator('button.dismiss-btn').click();
    const req = await posted;
    expect(fromBinary(
      DismissArtistRecommendationRequestSchema,
      unframeGrpcWebRequest(req.postDataBuffer()),
    ).suggestedArtistMbid).toBe('abc-1');

    // Local state filter drops the dismissed row; only Coltrane left.
    await expect(cards).toHaveCount(1);
    await expect(cards.first().locator('.artist-name')).toContainText('John Coltrane');
  });

  test('Refresh now fires RefreshArtistRecommendations and shows the in-flight state', async ({ page }) => {
    const refreshBtn = page.locator('app-discover button.refresh-btn');
    await expect(refreshBtn).toContainText('Refresh now');
    await expect(refreshBtn).not.toBeDisabled();

    const posted = page.waitForRequest(req =>
      req.url().endsWith(`${AS}/RefreshArtistRecommendations`),
    );
    await refreshBtn.click();
    await posted;

    // After the POST resolves but before the 4 s setTimeout fires,
    // the button stays disabled and shows the in-flight label. We
    // don't wait for the second-load — the test ends well before
    // the timer fires and the page tearsdown the timer with it.
    await expect(refreshBtn).toContainText('Refreshing');
    await expect(refreshBtn).toBeDisabled();
  });
});
