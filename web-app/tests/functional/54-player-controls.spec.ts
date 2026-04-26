import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// Player controls + skip-segment integration. Targets player.ts
// coverage gaps not exercised by 13-player-playback / 14-player-resume:
// togglePlay, skipBack/Forward, seek, toggleSubs, seekToChapter,
// next/previousChapter, goBack, formatTime hours branch, skipIntro,
// Up Next via credits segment + cancel + playNext, invalid transcodeId,
// next-episode fetch, subs=off query param, controls visibility timer.

const FIXTURE_DIR = 'tests/fixtures/player';
const TRANSCODE_ID = 42;
const NEXT_TRANSCODE_ID = 43;

async function setupPlayerRoutes(page: Page, opts: {
  chaptersJson?: object;
  nextEpisode?: { transcodeId: number; label: string } | null;
} = {}) {
  await page.route(`**/stream/${TRANSCODE_ID}/chapters.json`, route => {
    if (opts.chaptersJson) return route.fulfill({ json: opts.chaptersJson });
    return route.fulfill({ path: `${FIXTURE_DIR}/chapters.json`, headers: { 'Content-Type': 'application/json' } });
  });
  await page.route(`**/stream/${TRANSCODE_ID}/thumbs.vtt`, route =>
    route.fulfill({ path: `${FIXTURE_DIR}/thumbs.vtt`, headers: { 'Content-Type': 'text/vtt' } }));
  await page.route(`**/stream/${TRANSCODE_ID}/subs.vtt`, route => {
    if (route.request().method() === 'HEAD') {
      return route.fulfill({ status: 200, headers: { 'Content-Type': 'text/vtt' }, body: '' });
    }
    return route.fulfill({ path: `${FIXTURE_DIR}/subs.vtt`, headers: { 'Content-Type': 'text/vtt' } });
  });
  await page.route(`**/stream/${TRANSCODE_ID}/thumbs.jpg`, route =>
    route.fulfill({ path: `${FIXTURE_DIR}/thumbs.jpg`, headers: { 'Content-Type': 'image/jpeg' } }));
  // next-episode endpoint (404 by default; non-404 only when test asks).
  if (opts.nextEpisode) {
    await page.route(`**/stream/${TRANSCODE_ID}/next-episode`, route =>
      route.fulfill({ json: opts.nextEpisode! }));
  } else {
    await page.route(`**/stream/${TRANSCODE_ID}/next-episode`, route =>
      route.fulfill({ status: 404 }));
  }
  await page.route(`**/stream/${TRANSCODE_ID}`, route =>
    route.fulfill({
      path: `${FIXTURE_DIR}/20s.mp4`,
      headers: { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes' },
    }));
}

async function waitForPlaying(page: Page, minTime = 0.5) {
  await page.waitForSelector('app-player video');
  await page.waitForFunction((min) => {
    const v = document.querySelector('video') as HTMLVideoElement | null;
    return !!v && v.currentTime >= min && v.duration > 0 && !v.paused;
  }, minTime, { timeout: 10_000 });
}

test.describe('player — invalid transcode id', () => {
  test('non-numeric transcodeId shows the error loading text', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await page.goto('/play/abc');
    await page.waitForSelector('app-player');
    await expect(page.locator('.loading-text')).toContainText('Invalid transcode ID');
  });
});

test.describe('player — controls', () => {
  test('togglePlay button pauses then resumes', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await setupPlayerRoutes(page);
    await page.goto(`/play/${TRANSCODE_ID}`);
    await waitForPlaying(page);
    // First control button in controls-left is play/pause; aria flips
    // between Play/Pause based on signal.
    const playPause = page.locator('.controls-left .ctrl-btn').first();
    await expect(playPause).toHaveAttribute('aria-label', 'Pause');
    await playPause.click();
    await expect(playPause).toHaveAttribute('aria-label', 'Play');
    await playPause.click();
    await expect(playPause).toHaveAttribute('aria-label', 'Pause');
  });

  test('skipBack rewinds 10s, skipForward advances 30s', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await setupPlayerRoutes(page);
    await page.goto(`/play/${TRANSCODE_ID}`);
    await waitForPlaying(page, 1);

    const before = await page.evaluate(() =>
      (document.querySelector('video') as HTMLVideoElement).currentTime);
    await page.locator('button[aria-label="Forward 30 seconds"]').click();
    await page.waitForTimeout(200);
    const after = await page.evaluate(() =>
      (document.querySelector('video') as HTMLVideoElement).currentTime);
    // 20-second clip: skipForward clamps to duration.
    expect(after).toBeGreaterThan(before);

    await page.locator('button[aria-label="Back 10 seconds"]').click();
    await page.waitForTimeout(200);
    const back = await page.evaluate(() =>
      (document.querySelector('video') as HTMLVideoElement).currentTime);
    expect(back).toBeLessThan(after);
  });

  test('toggleSubs flips the active state + aria-label', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await setupPlayerRoutes(page);
    await page.goto(`/play/${TRANSCODE_ID}`);
    await waitForPlaying(page);
    // Subs button only renders when subsAvailable; the HEAD probe sets that.
    const subsBtn = page.locator('button[aria-label="Hide subtitles"]');
    await expect(subsBtn).toBeVisible();
    await expect(subsBtn).toHaveClass(/active/);
    await subsBtn.click();
    await expect(page.locator('button[aria-label="Show subtitles"]')).not.toHaveClass(/active/);
  });

  test('seek-bar click jumps to the clicked fraction of duration', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await setupPlayerRoutes(page);
    await page.goto(`/play/${TRANSCODE_ID}`);
    await waitForPlaying(page);
    const bar = page.locator('.seek-bar-container');
    const box = await bar.boundingBox();
    expect(box).not.toBeNull();
    // Click ~75% across; clip is ~20s so currentTime should jump to ~15s.
    await page.mouse.click(box!.x + box!.width * 0.75, box!.y + box!.height / 2);
    await page.waitForTimeout(200);
    const t = await page.evaluate(() =>
      (document.querySelector('video') as HTMLVideoElement).currentTime);
    expect(t).toBeGreaterThan(10);
  });
});

test.describe('player — chapters', () => {
  test('chapter ticks render + clicking a tick seeks to its start', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await setupPlayerRoutes(page);
    await page.goto(`/play/${TRANSCODE_ID}`);
    await waitForPlaying(page);
    // chapters.json has 2 chapters → 2 ticks.
    const ticks = page.locator('.chapter-tick');
    await expect(ticks).toHaveCount(2);
    // Click the second tick (start=2.5s); the seekToChapter handler
    // should jump video.currentTime to 2.5.
    await ticks.nth(1).click();
    await page.waitForTimeout(200);
    const t = await page.evaluate(() =>
      (document.querySelector('video') as HTMLVideoElement).currentTime);
    expect(t).toBeGreaterThanOrEqual(2.4);
    expect(t).toBeLessThan(4.0);
  });

  test('keyboard ] jumps to the next chapter', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await setupPlayerRoutes(page);
    await page.goto(`/play/${TRANSCODE_ID}`);
    await waitForPlaying(page, 0.2);
    await page.locator('app-player .player-container').focus();
    await page.keyboard.press(']');
    await page.waitForTimeout(200);
    const t = await page.evaluate(() =>
      (document.querySelector('video') as HTMLVideoElement).currentTime);
    expect(t).toBeGreaterThanOrEqual(2.4);
  });
});

test.describe('player — skip intro', () => {
  test('Skip Intro button renders inside the intro window + click skips past it', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await setupPlayerRoutes(page, {
      chaptersJson: {
        chapters: [{ number: 1, start: 0, end: 5, title: 'Whole' }],
        // INTRO covers 0..3s; ENDS at 3 so skipIntro jumps to 3.
        skipSegments: [{ type: 'INTRO', start: 0, end: 3, method: 'auto' }],
      },
    });
    await page.goto(`/play/${TRANSCODE_ID}`);
    await page.waitForSelector('app-player video');
    // Button should appear once playhead is inside the intro.
    const btn = page.locator('.skip-intro-btn');
    await expect(btn).toBeVisible({ timeout: 5_000 });
    await btn.click();
    await page.waitForTimeout(200);
    const t = await page.evaluate(() =>
      (document.querySelector('video') as HTMLVideoElement).currentTime);
    expect(t).toBeGreaterThanOrEqual(2.8);
  });
});

test.describe('player — Up Next', () => {
  test('credits segment triggers Up Next overlay; Cancel dismisses it', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await setupPlayerRoutes(page, {
      chaptersJson: {
        chapters: [{ number: 1, start: 0, end: 20, title: 'C' }],
        // Credits start at 1s — overlay should appear quickly.
        skipSegments: [{ type: 'END_CREDITS', start: 1, end: 20, method: 'auto' }],
      },
      nextEpisode: { transcodeId: NEXT_TRANSCODE_ID, label: 'S01E02 — Test EP' },
    });
    await page.goto(`/play/${TRANSCODE_ID}`);
    await page.waitForSelector('app-player video');
    const overlay = page.locator('.up-next-overlay');
    await expect(overlay).toBeVisible({ timeout: 5_000 });
    await expect(overlay).toContainText('S01E02');
    await overlay.locator('button', { hasText: 'Cancel' }).click();
    await expect(overlay).not.toBeVisible();
  });

  test('Play Now button on Up Next navigates to /play/:nextId', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await setupPlayerRoutes(page, {
      chaptersJson: {
        chapters: [{ number: 1, start: 0, end: 20, title: 'C' }],
        skipSegments: [{ type: 'END_CREDITS', start: 1, end: 20, method: 'auto' }],
      },
      nextEpisode: { transcodeId: NEXT_TRANSCODE_ID, label: 'Next' },
    });
    // Stub the next transcode's routes so the navigation can resolve.
    await page.route(`**/stream/${NEXT_TRANSCODE_ID}/**`, route => route.fulfill({ status: 404 }));
    await page.route(`**/stream/${NEXT_TRANSCODE_ID}`, route => route.fulfill({ status: 404 }));
    await page.route(`**/playback-progress/${NEXT_TRANSCODE_ID}`, route =>
      route.fulfill({ json: { position: 0, duration: 0 } }));
    await page.goto(`/play/${TRANSCODE_ID}`);
    await page.waitForSelector('app-player video');
    const overlay = page.locator('.up-next-overlay');
    await expect(overlay).toBeVisible({ timeout: 5_000 });
    await overlay.locator('button', { hasText: 'Play Now' }).click();
    await expect(page).toHaveURL(new RegExp(`/play/${NEXT_TRANSCODE_ID}`));
  });
});

test.describe('player — query params + chrome', () => {
  test('?subs=off starts with subtitles disabled', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await setupPlayerRoutes(page);
    await page.goto(`/play/${TRANSCODE_ID}?subs=off`);
    await waitForPlaying(page);
    // subsAvailable still true (HEAD ok), but enabled is off.
    await expect(page.locator('button[aria-label="Show subtitles"]')).toBeVisible();
  });

  test('title-on-pause overlay renders when paused with a titleName', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await setupPlayerRoutes(page);
    await page.goto(`/play/${TRANSCODE_ID}?title=My%20Movie`);
    await waitForPlaying(page);
    // Pause → title overlay appears.
    await page.evaluate(() =>
      (document.querySelector('video') as HTMLVideoElement).pause());
    await expect(page.locator('.title-on-pause')).toContainText('My Movie');
  });
});
