import type { Page } from '@playwright/test';

/** The transcode the player fixtures (tests/fixtures/player) stand in for. */
export const PLAYER_TRANSCODE_ID = 42;
/** The next episode `setupPlayerRoutes` can offer. */
export const PLAYER_NEXT_TRANSCODE_ID = 43;

const FIXTURE_DIR = 'tests/fixtures/player';

/**
 * Route the player's media endpoints for transcode 42 to the fixtures
 * under tests/fixtures/player: a 20 s synthetic MP4, chapters, subtitles,
 * and scrub thumbnails. `nextEpisode` makes the next-episode endpoint
 * answer instead of 404. Shared by the player specs and the docs
 * screenshots.
 */
export async function setupPlayerRoutes(page: Page, opts: {
  chaptersJson?: object;
  nextEpisode?: { transcodeId: number; label: string } | null;
} = {}): Promise<void> {
  const id = PLAYER_TRANSCODE_ID;
  await page.route(`**/stream/${id}/chapters.json`, route => {
    if (opts.chaptersJson) return route.fulfill({ json: opts.chaptersJson });
    return route.fulfill({ path: `${FIXTURE_DIR}/chapters.json`, headers: { 'Content-Type': 'application/json' } });
  });
  await page.route(`**/stream/${id}/thumbs.vtt`, route =>
    route.fulfill({ path: `${FIXTURE_DIR}/thumbs.vtt`, headers: { 'Content-Type': 'text/vtt' } }));
  await page.route(`**/stream/${id}/subs.vtt`, route => {
    if (route.request().method() === 'HEAD') {
      return route.fulfill({ status: 200, headers: { 'Content-Type': 'text/vtt' }, body: '' });
    }
    return route.fulfill({ path: `${FIXTURE_DIR}/subs.vtt`, headers: { 'Content-Type': 'text/vtt' } });
  });
  await page.route(`**/stream/${id}/thumbs.jpg`, route =>
    route.fulfill({ path: `${FIXTURE_DIR}/thumbs.jpg`, headers: { 'Content-Type': 'image/jpeg' } }));
  // next-episode endpoint (404 by default; non-404 only when asked).
  if (opts.nextEpisode) {
    await page.route(`**/stream/${id}/next-episode`, route =>
      route.fulfill({ json: opts.nextEpisode! }));
  } else {
    await page.route(`**/stream/${id}/next-episode`, route =>
      route.fulfill({ status: 404 }));
  }
  await page.route(`**/stream/${id}`, route =>
    route.fulfill({
      path: `${FIXTURE_DIR}/20s.mp4`,
      headers: { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes' },
    }));
}
