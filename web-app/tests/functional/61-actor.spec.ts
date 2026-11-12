import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// /actor/:personId — ActorComponent. The server sends the hero headshot
// as a same-origin `/headshots/{castMemberId}` URL (served from the
// image cache, the same URL the search suggestions and results use),
// while "Other Works" posters arrive as TMDB paths that the page routes
// through the TMDB image proxy. The hero must use the server's URL as
// given; wrapping it in the proxy route produces a path the proxy
// rejects, and the hero renders broken on every actor page.

test.describe('actor page', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/actor/6384');
    await page.waitForSelector('app-actor .hero');
  });

  test('hero headshot uses the server headshot URL unchanged', async ({ page }) => {
    const hero = page.locator('app-actor img.profile-img');
    await expect(hero).toHaveAttribute('alt', 'Keanu Reeves');
    await expect(hero).toHaveAttribute('src', /^(https?:\/\/[^/]+)?\/headshots\/6384$/);
  });

  test('other-works posters go through the TMDB image proxy', async ({ page }) => {
    const poster = page.locator('app-actor .grid').last().locator('img').first();
    await expect(poster).toHaveAttribute('src', /\/proxy\/tmdb\/w185\/[A-Za-z0-9]+\.(jpg|png|webp)$/);
  });
});
