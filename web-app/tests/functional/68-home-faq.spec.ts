import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto } from '../helpers/proto-fixture';
import { create } from '@bufbuild/protobuf';
import { FeaturesSchema, HomeFeedResponseSchema } from '../../src/app/proto-gen/catalog_pb';
import { featuresViewer, homeFeedPopulated } from '../fixtures-typed/home-feed.fixture';

// FAQ accordion (spec 3.9) at the end of home: a centered column of
// question rows that expand to their answers, with aria-expanded and
// aria-controls on each button. The live TV entry appears only when live
// TV is configured.

const faq = (page: Page) => page.locator('app-home app-faq-accordion');

test.describe('home FAQ', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await page.waitForSelector('app-home app-faq-accordion');
  });

  test('is the last section and lists the questions collapsed', async ({ page }) => {
    await expect(faq(page).locator('h2')).toHaveText('Frequently asked questions');
    const buttons = faq(page).locator('button.faq-q');
    await expect(buttons).toHaveCount(6);
    await expect(buttons.first()).toHaveText(/What is Media Manager\?/);
    await expect(buttons.last()).toHaveText(/Can I watch live TV\?/);
    for (let i = 0; i < 6; i++) await expect(buttons.nth(i)).toHaveAttribute('aria-expanded', 'false');
    await expect(faq(page).locator('.faq-a:visible')).toHaveCount(0);
    const last = page.locator('app-home .home-container > *').last();
    await expect(last).toHaveJSProperty('tagName', 'APP-FAQ-ACCORDION');
  });

  test('a question expands its answer and collapses again, with matching ids', async ({ page }) => {
    const q = faq(page).locator('button.faq-q').nth(2);
    const controls = await q.getAttribute('aria-controls');
    const answer = page.locator(`#${controls}`);
    await expect(answer).toBeHidden();
    await q.click();
    await expect(q).toHaveAttribute('aria-expanded', 'true');
    await expect(answer).toBeVisible();
    await expect(answer).toContainText('Continue Watching, Resume Reading, and Continue Listening');
    await expect(answer).toHaveAttribute('aria-labelledby', (await q.getAttribute('id'))!);
    await q.click();
    await expect(q).toHaveAttribute('aria-expanded', 'false');
    await expect(answer).toBeHidden();
  });

  test('more than one answer can be open at once', async ({ page }) => {
    const buttons = faq(page).locator('button.faq-q');
    await buttons.nth(0).click();
    await buttons.nth(1).click();
    await expect(faq(page).locator('.faq-a:visible')).toHaveCount(2);
  });
});

test.describe('home FAQ without live TV', () => {
  test('omits the live TV question', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    const noLive = create(HomeFeedResponseSchema, {
      ...homeFeedPopulated,
      features: create(FeaturesSchema, { ...featuresViewer, hasLiveTv: false }),
    });
    await page.route('**/mediamanager.CatalogService/HomeFeed', r => fulfillProto(r, HomeFeedResponseSchema, noLive));
    await page.goto('/');
    await page.waitForSelector('app-home app-faq-accordion');
    await expect(faq(page).locator('button.faq-q')).toHaveCount(5);
    await expect(faq(page).locator('button.faq-q', { hasText: 'live TV' })).toHaveCount(0);
  });
});

// The accordion sits inside the padded home container. At phone width its
// host must take the width it is given, gutters included, or the whole
// page gains a sideways scroll (seen on the NAS at 390px: 16px over).
test.describe('home FAQ at phone width', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/');
    await page.waitForSelector('app-home app-faq-accordion');
  });

  test('fits the phone width without sideways scroll', async ({ page }) => {
    const overflow = await page.evaluate(() => {
      const root = document.documentElement;
      return root.scrollWidth - root.clientWidth;
    });
    expect(overflow).toBe(0);

    const box = (await faq(page).boundingBox())!;
    const container = (await page.locator('app-home .home-container').boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(container.x);
    expect(box.x + box.width).toBeLessThanOrEqual(container.x + container.width + 0.5);
    expect(box.x + box.width).toBeLessThanOrEqual(390);
  });
});
