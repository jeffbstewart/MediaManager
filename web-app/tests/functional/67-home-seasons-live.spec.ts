import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto } from '../helpers/proto-fixture';
import { create } from '@bufbuild/protobuf';
import { FeaturesSchema, HomeFeedResponseSchema } from '../../src/app/proto-gen/catalog_pb';
import { featuresViewer, homeFeedPopulated } from '../fixtures-typed/home-feed.fixture';

// New Seasons Available as wide cards with the amber "New S4" badge and
// the title's backdrop (spec 3.3), and the On Live TV row: one wide card
// per channel (first ten) with the LIVE badge, opening the channel's
// player. Fixture: Breaking Bad 401 missing season 4; channels WTTW HD
// 5.1 PBS, WLS HD 7.1 ABC, WGN HD 9.1 Independent.

const seasons = (page: Page) => page.locator('app-home .carousel-section', { hasText: 'New Seasons Available' });
const live = (page: Page) => page.locator('app-home .carousel-section', { hasText: 'On Live TV' });

async function setup(page: Page) {
  await mockBackend(page);
  await loginAs(page);
  await stubImages(page);
  await page.goto('/');
  await page.waitForSelector('app-home app-wide-card');
}

test.describe('home new seasons and live TV rows', () => {
  test('a new season is a wide card with the backdrop, an amber badge, and the missing seasons as meta', async ({ page }) => {
    await setup(page);
    const card = seasons(page).locator('app-wide-card').first();
    await expect(card.locator('.art img')).toHaveAttribute('src', /\/backdrops\/401$/);
    await expect(card.locator('.overlay-title')).toHaveText('Breaking Bad');
    await expect(card.locator('.badge')).toHaveText('New S4');
    await expect(card.locator('.badge')).toHaveClass(/accent/);
    await expect(card.locator('.caption .m')).toHaveText('Missing S4');
    await expect(card.locator('a.card-link')).toHaveAttribute('href', /\/title\/401$/);
    await expect(card.getByRole('button', { name: 'Dismiss Breaking Bad' })).toHaveCount(1);
  });

  test('a backdrop that fails to load gives way to placeholder art', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route('**/backdrops/401', r => r.fulfill({ status: 404, body: '' }));
    await page.goto('/');
    const card = seasons(page).locator('app-wide-card').first();
    // The art loads lazily, so bring the card into view for the request to happen and fail.
    await card.scrollIntoViewIfNeeded();
    await expect(card.locator('app-placeholder-art')).toHaveCount(1);
    await expect(card.locator('.art img')).toHaveCount(0);
  });

  test('On Live TV lists the channels as wide cards with the LIVE badge, linking to each channel', async ({ page }) => {
    await setup(page);
    const cards = live(page).locator('app-wide-card');
    await expect(cards).toHaveCount(3);
    const first = cards.nth(0);
    await expect(first.locator('.overlay-title')).toHaveText('WTTW HD');
    await expect(first.locator('.badge')).toHaveText('Live');
    await expect(first.locator('.caption .t')).toHaveText('WTTW HD • PBS');
    await expect(first.locator('.caption .m')).toHaveText('Channel 5.1');
    await expect(first.locator('a.card-link')).toHaveAttribute('href', /\/live-tv\/1\?name=5\.1(\+|%20)WTTW(\+|%20)HD$/);
    // No channel art exists, so every card shows placeholder art.
    await expect(first.locator('app-placeholder-art')).toHaveCount(1);
    await expect(live(page).locator('h2 a, a.see-all')).toHaveAttribute('href', /\/live-tv$/);
  });

  test('On Live TV follows New Seasons Available', async ({ page }) => {
    await setup(page);
    const headings = await page.locator('app-home .home-container > section h2').evaluateAll(els =>
      els.map(el => el.textContent!.trim()));
    expect(headings.indexOf('On Live TV')).toBe(headings.indexOf('New Seasons Available') + 1);
  });

  test('without live TV there is no On Live TV row and no channel request', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    const noLive = create(HomeFeedResponseSchema, {
      ...homeFeedPopulated,
      features: create(FeaturesSchema, { ...featuresViewer, hasLiveTv: false }),
    });
    await page.route('**/mediamanager.CatalogService/HomeFeed', r => fulfillProto(r, HomeFeedResponseSchema, noLive));
    let channelRequests = 0;
    page.on('request', req => { if (req.url().endsWith('/mediamanager.LiveService/ListTvChannels')) channelRequests++; });
    await page.goto('/');
    await page.waitForSelector('app-home app-wide-card');
    await expect(live(page)).toHaveCount(0);
    expect(channelRequests).toBe(0);
  });
});
