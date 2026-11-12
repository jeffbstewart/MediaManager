import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto } from '../helpers/proto-fixture';
import { clone } from '@bufbuild/protobuf';
import { FeaturesSchema, HomeFeedResponseSchema } from '../../src/app/proto-gen/catalog_pb';
import { featuresViewer, homeFeedPopulated } from '../fixtures-typed/home-feed.fixture';
import { expectView } from '../helpers/expect-view';

// Bottom tab bar (spec `nav.tabbar`). Below 1024px a fixed 64px bar
// carries Home, Live TV (when enabled), Movies & TV and Discover (when
// enabled); the active tab has aria-current="page". At 1024px and up
// it is not shown. Also covers the page glow's `data-page` attribute
// on body, which follows the same route sections.

const tabbar = (page: Page) => page.locator('nav.tabbar[aria-label="Tabs"]');
const tab = (page: Page, label: string) => tabbar(page).getByRole('link', { name: label, exact: true });
// The label spans only: each link also holds its icon's ligature text.
const labels = (page: Page) => tabbar(page).locator('a > span');

async function turnOff(page: Page, flags: { liveTv?: boolean; musicRadio?: boolean }) {
  const features = clone(FeaturesSchema, featuresViewer);
  if (flags.liveTv === false) features.hasLiveTv = false;
  if (flags.musicRadio === false) features.hasMusicRadio = false;
  const home = clone(HomeFeedResponseSchema, homeFeedPopulated);
  home.features = clone(FeaturesSchema, features);
  await page.route('**/mediamanager.CatalogService/GetFeatures', r =>
    fulfillProto(r, FeaturesSchema, features));
  await page.route('**/mediamanager.CatalogService/HomeFeed', r =>
    fulfillProto(r, HomeFeedResponseSchema, home));
}

test.describe('tab bar at phone width', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
    await page.setViewportSize({ width: 390, height: 844 });
  });

  test('shows four tabs fixed to the bottom of the viewport', async ({ page }) => {
    await page.goto('/');
    await expect(tab(page, 'Discover')).toBeVisible();
    await expect(labels(page)).toHaveText(['Home', 'Live TV', 'Movies & TV', 'Discover']);
    const box = await tabbar(page).boundingBox();
    expect(box).not.toBeNull();
    expect(box!.height).toBe(64);
    expect(box!.y + box!.height).toBe(844);
    await expect(tabbar(page)).toHaveCSS('position', 'fixed');
  });

  test('tabs link to their sections', async ({ page }) => {
    await page.goto('/');
    await expect(tab(page, 'Home')).toHaveAttribute('href', /\/$/);
    await expect(tab(page, 'Live TV')).toHaveAttribute('href', /\/live-tv$/);
    await expect(tab(page, 'Movies & TV')).toHaveAttribute('href', /\/content\/movies$/);
    await expect(tab(page, 'Discover')).toHaveAttribute('href', /\/discover$/);
  });

  test('the active tab follows the route', async ({ page }) => {
    await page.goto('/');
    await expect(tab(page, 'Home')).toHaveAttribute('aria-current', 'page');
    await expect(tabbar(page).locator('[aria-current="page"]')).toHaveCount(1);

    await tab(page, 'Movies & TV').click();
    await expectView(page, /\/content\/movies$/, 'app-movies');
    await expect(tab(page, 'Movies & TV')).toHaveAttribute('aria-current', 'page');
    await expect(tab(page, 'Home')).not.toHaveAttribute('aria-current', /.*/);

    // TV Shows belongs to the same tab.
    await page.goto('/content/tv');
    await expect(tab(page, 'Movies & TV')).toHaveAttribute('aria-current', 'page');

    // Pages outside the four sections leave every tab inactive.
    await page.goto('/content/tags');
    await expect(tabbar(page)).toBeVisible();
    await expect(tabbar(page).locator('[aria-current="page"]')).toHaveCount(0);
  });

  test('main ends above the tab bar', async ({ page }) => {
    await page.goto('/');
    await expect(tabbar(page)).toBeVisible();
    await expect(page.locator('main.app-main')).toHaveCSS('padding-bottom', '64px');
  });

  test('Live TV and Discover drop out when their features are off', async ({ page }) => {
    await turnOff(page, { liveTv: false, musicRadio: false });
    await page.goto('/');
    await expect(tab(page, 'Home')).toBeVisible();
    await expect(labels(page)).toHaveText(['Home', 'Movies & TV']);
  });
});

test.describe('tab bar at desktop width', () => {
  test('is not shown at 1024px and up', async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
    await page.setViewportSize({ width: 1024, height: 768 });
    await page.goto('/');
    await expect(page.locator('aside.side-nav nav[aria-label="Primary"]')).toBeVisible();
    await expect(tabbar(page)).toBeHidden();
    await expect(page.locator('main.app-main')).toHaveCSS('padding-bottom', '0px');
  });
});

test.describe('page glow', () => {
  test('body data-page follows the route section', async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await expect(page.locator('.page-glow')).toHaveAttribute('aria-hidden', 'true');
    await expect(page.locator('body')).toHaveAttribute('data-page', 'home');

    await page.goto('/discover');
    await expect(page.locator('body')).toHaveAttribute('data-page', 'discover');

    await page.goto('/content/movies');
    await expect(page.locator('body')).not.toHaveAttribute('data-page', /.*/);
  });
});
