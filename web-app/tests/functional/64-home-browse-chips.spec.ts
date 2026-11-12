import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto } from '../helpers/proto-fixture';
import { create } from '@bufbuild/protobuf';
import { FeaturesSchema, HomeFeedResponseSchema } from '../../src/app/proto-gen/catalog_pb';
import { featuresViewer, homeFeedPopulated } from '../fixtures-typed/home-feed.fixture';
import { expectView } from '../helpers/expect-view';

// Browse Your Library (spec 3.5, home composition row 3): a scrolling row
// of 40px chips, one per library section the nav shows, in the order of
// the Marquee prototype, gated by the same feature flags as the nav.

const browse = (page: Page) => page.locator('app-home section[aria-label="Browse your library"]');

/** Chip labels without the icon ligature text that precedes them in textContent. */
const chipLabels = (page: Page) => browse(page).locator('a.chip').evaluateAll(els =>
  els.map(a => a.textContent!.replace(a.querySelector('mat-icon')?.textContent ?? '', '').trim()));

const ALL_CHIPS: [string, string][] = [
  ['Movies', '/content/movies'], ['TV Shows', '/content/tv'], ['Books', '/content/books'],
  ['Music', '/content/music'], ['Collections', '/content/collections'], ['Family', '/content/family'],
  ['Live TV', '/live-tv'], ['Cameras', '/cameras'], ['Playlists', '/content/playlists'],
  ['Tags', '/content/tags'], ['My Wish List', '/wishlist'],
];

test.describe('home browse chips', () => {
  test('shows one chip per library section, in order, linking to its page', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    const chips = browse(page).locator('a.chip');
    await expect(chips).toHaveCount(ALL_CHIPS.length);
    const labels = await chipLabels(page);
    const hrefs = await chips.evaluateAll(els => els.map(a => a.getAttribute('href')));
    expect(labels.map((l, i) => [l, hrefs[i]])).toEqual(ALL_CHIPS);
    await expect(browse(page).locator('h2')).toHaveText('Browse Your Library');
    await expect(chips.first().locator('mat-icon')).toHaveAttribute('aria-hidden', 'true');
  });

  test('sits between the featured carousel and Continue Watching', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await page.waitForSelector('app-home section[aria-label="Browse your library"]');
    const labels = await page.locator('app-home .home-container > section').evaluateAll(els =>
      els.map(el => el.getAttribute('aria-label') ?? el.querySelector('h2')?.textContent?.trim() ?? ''));
    const i = labels.indexOf('Browse your library');
    expect(labels[i - 1]).toBe('Featured');
    expect(labels[i + 1]).toBe('Continue Watching');
  });

  test('hides the chips for sections the library does not have', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    const trimmed = create(HomeFeedResponseSchema, {
      ...homeFeedPopulated,
      features: create(FeaturesSchema, {
        ...featuresViewer, hasBooks: false, hasMusic: false, hasPersonalVideos: false, hasLiveTv: false, hasCameras: false,
      }),
    });
    await page.route('**/mediamanager.CatalogService/HomeFeed', r => fulfillProto(r, HomeFeedResponseSchema, trimmed));
    await page.goto('/');
    await expect(browse(page).locator('a.chip')).toHaveCount(5);
    expect(await chipLabels(page)).toEqual(['Movies', 'TV Shows', 'Collections', 'Tags', 'My Wish List']);
  });

  test('a chip navigates to its section', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await browse(page).getByRole('link', { name: 'Books' }).click();
    await expectView(page, /\/content\/books$/, 'app-books');
  });
});
