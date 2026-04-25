import type { Page, Route } from '@playwright/test';

/**
 * 1×1 grey PNG. Decoded lazily once and handed to Playwright's route
 * fulfill() as the body for every poster / headshot / album-art URL.
 * Axe doesn't inspect image pixels, only alt attributes + dimensions,
 * so a single tiny PNG covers every image endpoint the app queries.
 */
const PLACEHOLDER_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4//8/AwAI/AL+XJ/PswAAAABJRU5ErkJggg==';

/**
 * Register catch-all handlers for every image endpoint that the web-app
 * may request. Each returns a shared 1×1 PNG. Registered on the page
 * before navigation so the first render finds them already in place.
 */
export async function stubImages(page: Page): Promise<void> {
  const handler = (r: Route) =>
    r.fulfill({
      status: 200,
      headers: { 'Cache-Control': 'no-store', 'Content-Type': 'image/png' },
      body: Buffer.from(PLACEHOLDER_PNG_BASE64, 'base64'),
    });

  const globs = [
    '**/posters/**',
    '**/headshots/**',
    '**/author-headshots/**',
    '**/artist-headshots/**',
    '**/backdrops/**',
    '**/collection-posters/**',
    '**/local-images/**',
    '**/ownership-photos/**',
    '**/public/album-art/**',
    '**/cam/**',
    'https://image.tmdb.org/**',
  ];
  for (const g of globs) {
    await page.route(g, handler);
  }
}
