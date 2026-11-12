import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { expectView } from '../helpers/expect-view';

// Home hero banners (spec 3.4): the featured carousel at the top of the
// page and the spotlight hero after Recently Added. Each banner fills
// with the server's backdrop tint and fades it into the backdrop, so
// every title gets its own colour. Fixture: featured Inception (101,
// playable, transcode 11, tint #134b53) and Interstellar (102, not
// playable, tint #4e1d18); spotlight Breaking Bad (401, tint #5e4308).

const featured = (page: Page) => page.locator('app-home section[aria-label="Featured"]');
const spotlight = (page: Page) => page.locator('app-home section[aria-label="Spotlight"]');

async function tintOf(locator: import('@playwright/test').Locator): Promise<{ tint: string; background: string }> {
  return locator.evaluate(el => {
    const cs = getComputedStyle(el);
    return { tint: cs.getPropertyValue('--tint').trim(), background: cs.backgroundColor };
  });
}

test.describe('home heroes', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await page.waitForSelector('app-home .hero-section');
  });

  test('the featured carousel shows one banner per title, first one eager', async ({ page }) => {
    const banners = featured(page).locator('app-hero-banner');
    await expect(banners).toHaveCount(2);
    await expect(banners.nth(0).locator('h2')).toHaveText('Inception');
    await expect(banners.nth(1).locator('h2')).toHaveText('Interstellar');
    await expect(banners.nth(0).locator('img')).not.toHaveAttribute('loading', 'lazy');
    await expect(banners.nth(1).locator('img')).toHaveAttribute('loading', 'lazy');
    await expect(banners.nth(0).locator('img')).toHaveAttribute('src', /\/backdrops\/101$/);
    // Two slides, so the dots render.
    await expect(featured(page).locator('.hero-dots button')).toHaveCount(2);
  });

  test('each banner is filled with its own backdrop tint', async ({ page }) => {
    const heroes = featured(page).locator('article.hero');
    const first = await tintOf(heroes.nth(0));
    const second = await tintOf(heroes.nth(1));
    expect(first).toEqual({ tint: '#134b53', background: 'rgb(19, 75, 83)' });
    expect(second).toEqual({ tint: '#4e1d18', background: 'rgb(78, 29, 24)' });
  });

  test('a playable banner offers Play and Details; an unplayable one only Details', async ({ page }) => {
    const first = featured(page).locator('app-hero-banner').nth(0);
    await expect(first.getByRole('link', { name: 'Play' })).toHaveAttribute('href', /\/play\/11$/);
    await expect(first.getByRole('link', { name: 'Details' })).toHaveAttribute('href', /\/title\/101$/);
    const second = featured(page).locator('app-hero-banner').nth(1);
    await expect(second.getByRole('link', { name: 'Play' })).toHaveCount(0);
    await expect(second.getByRole('link', { name: 'Details' })).toHaveAttribute('href', /\/title\/102$/);
  });

  test('Play on the featured banner navigates to the player', async ({ page }) => {
    await featured(page).getByRole('link', { name: 'Play' }).first().click();
    await expectView(page, /\/play\/11(?:\?|$)/, 'app-player');
  });

  test('the spotlight hero shows the show with a new season and links to its page', async ({ page }) => {
    const hero = spotlight(page).locator('app-hero-banner');
    await expect(hero).toHaveCount(1);
    await expect(hero.locator('h2')).toHaveText('Breaking Bad');
    await expect(hero.locator('.hero-kicker')).toHaveText('New season available');
    await expect(hero.getByRole('link', { name: 'Play' })).toHaveCount(0);
    expect(await tintOf(hero.locator('article.hero'))).toEqual({ tint: '#5e4308', background: 'rgb(94, 67, 8)' });
    await hero.getByRole('link', { name: 'Details' }).click();
    await expectView(page, /\/title\/401$/, 'app-title-detail');
  });

  test('the featured carousel follows the welcome block and the spotlight sits after Recently Added', async ({ page }) => {
    const sections = await page.locator('app-home .home-container > section').evaluateAll(els =>
      els.map(el => el.classList.contains('welcome') ? 'welcome'
        : (el.getAttribute('aria-label') ?? el.querySelector('h2')?.textContent?.trim() ?? '')));
    expect(sections.indexOf('Featured')).toBe(sections.indexOf('welcome') + 1);
    expect(sections.indexOf('Spotlight')).toBe(sections.indexOf('Recently Added') + 1);
  });

  test('below 1024px the banner stacks the image above the text', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const hero = featured(page).locator('article.hero').first();
    expect(await hero.evaluate(el => getComputedStyle(el).flexDirection)).toBe('column');
    const img = await hero.locator('.hero-img').boundingBox();
    const title = await hero.locator('h2').boundingBox();
    expect(img!.y).toBeLessThan(title!.y);
  });
});

test.describe('home heroes, empty feed', () => {
  test('no hero sections render when the feed has none', async ({ page }) => {
    await mockBackend(page, { homeFeed: 'empty' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await page.waitForSelector('app-home');
    await expect(page.locator('app-home .hero-section')).toHaveCount(0);
  });
});
