import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto } from '../helpers/proto-fixture';
import { create } from '@bufbuild/protobuf';
import { FeaturesSchema, HomeFeedResponseSchema } from '../../src/app/proto-gen/catalog_pb';
import { featuresViewer, homeFeedPopulated } from '../fixtures-typed/home-feed.fixture';

// Continue Watching as wide 16:9 cards (spec 3.3): backdrop art with the
// title over it, the time left as the caption's meta line, a progress
// bar, the card linking to the player and the caption title to the title
// page, the dismiss control kept. When live TV is on, the row ends with
// the All Channels tile. Fixture: The Matrix, transcode 1, 600 of 8160 s.

const row = (page: Page) => page.locator('app-home .carousel-section', { hasText: 'Continue Watching' });

async function setup(page: Page) {
  await mockBackend(page);
  await loginAs(page);
  await stubImages(page);
  await page.goto('/');
  await page.waitForSelector('app-home app-wide-card');
}

test.describe('home continue watching', () => {
  test('renders a wide card with the backdrop, title overlay, time left, and progress', async ({ page }) => {
    await setup(page);
    const card = row(page).locator('app-wide-card').first();
    await expect(card.locator('.art img')).toHaveAttribute('src', /\/backdrops\/100$/);
    await expect(card.locator('.overlay-title')).toHaveText('The Matrix');
    await expect(card.locator('.caption .t')).toHaveText('The Matrix');
    await expect(card.locator('.caption .m')).toHaveText('126 min left');
    await expect(card.locator('[role="progressbar"]')).toHaveAttribute('aria-valuenow', '7');
    const box = await card.locator('.art').boundingBox();
    expect(box!.width / box!.height).toBeCloseTo(16 / 9, 1);
  });

  test('the card plays, the caption title opens the title page, and dismiss stays', async ({ page }) => {
    await setup(page);
    const card = row(page).locator('app-wide-card').first();
    await expect(card.locator('a.card-link')).toHaveAttribute('href', /\/play\/1\?title=The(\+|%20)Matrix$/);
    await expect(card.locator('a.wide-title')).toHaveAttribute('href', /\/title\/100$/);
    await expect(card.getByRole('button', { name: 'Dismiss The Matrix' })).toHaveCount(1);
  });

  test('ends with the All Channels tile when live TV is on', async ({ page }) => {
    await setup(page);
    const tile = row(page).locator('a.tile-all');
    await expect(tile).toHaveCount(1);
    await expect(tile).toHaveText(/All Channels/);
    await expect(tile).toHaveAttribute('href', /\/live-tv$/);
    const items = await row(page).locator('app-media-row li').count();
    expect(await row(page).locator('app-media-row li').nth(items - 1).locator('a.tile-all').count()).toBe(1);
  });

  test('has no All Channels tile without live TV', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    const noLive = create(HomeFeedResponseSchema, {
      ...homeFeedPopulated,
      features: create(FeaturesSchema, { ...featuresViewer, hasLiveTv: false }),
    });
    await page.route('**/mediamanager.CatalogService/HomeFeed', r => fulfillProto(r, HomeFeedResponseSchema, noLive));
    await page.goto('/');
    await page.waitForSelector('app-home app-wide-card');
    await expect(row(page).locator('a.tile-all')).toHaveCount(0);
  });
});
