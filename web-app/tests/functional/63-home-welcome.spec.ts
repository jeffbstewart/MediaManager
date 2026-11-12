import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Welcome block (spec 3.8) at the top of home: "Welcome back, {username}"
// from the shared account load, an intro line, and a decorative mosaic of
// recent posters (recently added, recently watched, new seasons; each
// title once). Below 1024px it is a card with the mosaic above the text.
// Fixture: profile username "testuser"; posters 101, 102, 103, 401.

const welcome = (page: Page) => page.locator('app-home section.welcome');

test.describe('home welcome block', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await page.waitForSelector('app-home section.welcome');
  });

  test('greets the signed-in user by name', async ({ page }) => {
    await expect(welcome(page).locator('h1')).toHaveText('Welcome back, testuser');
    await expect(welcome(page).locator('p')).toContainText('Pick up where you left off');
    // The header pill shares the same account load and still shows the name.
    await expect(page.locator('.account-btn .label')).toHaveText('testuser');
  });

  test('the mosaic shows each recent poster once and is hidden from assistive tech', async ({ page }) => {
    const mosaic = welcome(page).locator('.mosaic');
    await expect(mosaic).toHaveAttribute('aria-hidden', 'true');
    const srcs = await mosaic.locator('img').evaluateAll(imgs => imgs.map(i => i.getAttribute('src')));
    expect(srcs).toEqual(['/posters/w185/101', '/posters/w185/102', '/posters/w185/103', '/posters/w185/401']);
    await expect(mosaic.locator('img').first()).toHaveAttribute('alt', '');
  });

  test('sits first on the page, ahead of the featured carousel', async ({ page }) => {
    const labels = await page.locator('app-home .home-container > section').evaluateAll(els =>
      els.map(el => el.classList.contains('welcome') ? 'welcome' : (el.getAttribute('aria-label') ?? '')));
    expect(labels[0]).toBe('welcome');
    expect(labels[1]).toBe('Featured');
  });

  test('below 1024px it becomes a rounded card with the mosaic above the text', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const card = welcome(page).locator('.welcome-card');
    expect(await card.evaluate(el => getComputedStyle(el).borderTopLeftRadius)).toBe('16px');
    const mosaic = await welcome(page).locator('.mosaic').boundingBox();
    const heading = await welcome(page).locator('h1').boundingBox();
    expect(mosaic!.y).toBeLessThan(heading!.y);
  });
});

test.describe('home welcome block, no feed', () => {
  test('still greets, with no mosaic', async ({ page }) => {
    await mockBackend(page, { homeFeed: 'empty' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await page.waitForSelector('app-home section.welcome');
    await expect(welcome(page).locator('h1')).toHaveText('Welcome back, testuser');
    await expect(welcome(page).locator('.mosaic')).toHaveCount(0);
  });
});
