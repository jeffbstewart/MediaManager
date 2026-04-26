import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Audio player bottom-bar integration test. Covers every button in
// the persistent bar plus the queue panel drop-up.
//
// The audio fixture is 30 s of digital silence (anullsrc → mp3,
// ~120 KB) so the test machine doesn't actually emit sound. Belt-
// and-braces: each test forces volume to 0 via mute before any audio
// can decode, in case the fixture's amplitude floor surprises us.
//
// Queue is seeded by navigating to the album page (title 301, two
// tracks: "So What" and "Freddie Freeloader") and clicking Play.
// That puts a real 2-track explicit queue in front of the bar so
// next/prev/queue-panel/jump all have something to operate on.
const ALBUM_ID = 301;
const FIXTURE_AUDIO = 'tests/fixtures/audio/silence.mp3';

async function setupAudio(page: Page) {
  await mockBackend(page);
  await loginAs(page);
  await stubImages(page);
  // /audio/{trackId} streams the silent clip. Single catch-all so
  // both tracks (4001, 4002) resolve.
  await page.route('**/audio/*', route =>
    route.fulfill({
      path: FIXTURE_AUDIO,
      headers: { 'Content-Type': 'audio/mpeg', 'Accept-Ranges': 'bytes' },
    }),
  );
  // Audio progress reports POST every ~10 s; default 204 keeps them
  // silent (no console errors when the test runs longer than that).
  await page.route('**/api/v2/audio/progress*', route =>
    route.fulfill({ status: 204 }),
  );
}

/**
 * Navigate to the album page and click Play, then wait for the
 * persistent bar to flip visible. Returns once the queue is seeded
 * and the audio element has a src.
 */
async function startAlbumPlayback(page: Page) {
  await page.goto(`/title/${ALBUM_ID}`);
  await page.waitForSelector('app-title-detail .album-actions');
  // The first .album-actions resume-btn is the big "Play" CTA.
  await page.locator('.album-actions button.resume-btn').first().click();
  await expect(page.locator('app-audio-player .audio-player')).toHaveClass(/visible/, { timeout: 2_000 });
  // Force mute right away so any decoded silence isn't even nominally
  // amplified — defence in depth against an audible-test surprise.
  await page.evaluate(() => {
    const v = document.querySelector('app-audio-player audio') as HTMLAudioElement | null;
    if (v) v.muted = true;
  });
}

test.describe('audio player bottom bar', () => {

  test.beforeEach(async ({ page }) => {
    await setupAudio(page);
    await startAlbumPlayback(page);
  });

  test('now-playing shows the first track + cover image', async ({ page }) => {
    const np = page.locator('app-audio-player .now-playing');
    await expect(np).toBeVisible();
    await expect(np.locator('.track-name')).toContainText('So What');
    await expect(np.locator('.track-album')).toContainText('Kind of Blue');
    await expect(np.locator('img.cover')).toBeVisible();
  });

  test('play / pause button toggles state', async ({ page }) => {
    const playBtn = page.locator('app-audio-player button.play-btn');
    // Playback starts on its own; the button label flips to "Pause".
    await expect(playBtn).toHaveAttribute('aria-label', 'Pause', { timeout: 2_000 });
    await playBtn.click();
    await expect(playBtn).toHaveAttribute('aria-label', 'Play');
    await playBtn.click();
    await expect(playBtn).toHaveAttribute('aria-label', 'Pause');
  });

  test('skip-next advances to the second track', async ({ page }) => {
    const trackName = page.locator('app-audio-player .now-playing .track-name');
    await expect(trackName).toContainText('So What');
    await page.locator('app-audio-player button[aria-label="Next track"]').click();
    await expect(trackName).toContainText('Freddie Freeloader');
  });

  test('skip-prev returns to the previous track', async ({ page }) => {
    const trackName = page.locator('app-audio-player .now-playing .track-name');
    // Advance first so prev has somewhere to go. Without the position
    // wait, prev's "if currentTime > 3 seek to 0" branch could fire.
    await page.locator('app-audio-player button[aria-label="Next track"]').click();
    await expect(trackName).toContainText('Freddie Freeloader');
    await page.locator('app-audio-player button[aria-label="Previous track"]').click();
    await expect(trackName).toContainText('So What');
  });

  test('volume slider updates the audio element', async ({ page }) => {
    // Set the slider to 0.3 and verify the underlying <audio>.volume
    // mirrors it (the volume signal effect writes to the element).
    const slider = page.locator('app-audio-player input.volume-slider');
    await slider.evaluate((el: HTMLInputElement) => {
      el.value = '0.3';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
    // muted forced in setup; un-mute first so the element honours the
    // value rather than holding mute.
    await page.locator('app-audio-player button[aria-label="Mute"]').click();
    const elementVolume = await page.evaluate(
      () => (document.querySelector('app-audio-player audio') as HTMLAudioElement).volume,
    );
    expect(elementVolume).toBeCloseTo(0.3, 2);
  });

  test('mute button flips icon and aria-label', async ({ page }) => {
    // Initial label is Mute (we forced muted=true on the element but
    // the queue.muted signal is still false — the icon reflects the
    // signal, not the element).
    const muteBtn = page.locator('app-audio-player .volume-cluster button').first();
    await expect(muteBtn).toHaveAttribute('aria-label', 'Mute');
    await muteBtn.click();
    await expect(muteBtn).toHaveAttribute('aria-label', 'Unmute');
    await muteBtn.click();
    await expect(muteBtn).toHaveAttribute('aria-label', 'Mute');
  });

  test('queue panel opens, lists both tracks, and clicking jumps', async ({ page }) => {
    // Toggle open.
    await page.locator('app-audio-player button[aria-label="Show queue"]').click();
    const panel = page.locator('app-queue-panel .queue-panel');
    await expect(panel).toBeVisible();
    const rows = panel.locator('.queue-row');
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toContainText('So What');
    await expect(rows.nth(1)).toContainText('Freddie Freeloader');

    // Jump to the second row; the bar's now-playing text follows.
    await rows.nth(1).click();
    await expect(page.locator('app-audio-player .now-playing .track-name'))
      .toContainText('Freddie Freeloader');
  });

  test('queue panel close button hides the panel', async ({ page }) => {
    await page.locator('app-audio-player button[aria-label="Show queue"]').click();
    await expect(page.locator('app-queue-panel .queue-panel')).toBeVisible();
    await page.locator('app-queue-panel button[aria-label="Close queue panel"]').click();
    await expect(page.locator('app-queue-panel .queue-panel')).toHaveCount(0);
  });

  test('shuffle button toggles the active class', async ({ page }) => {
    const btn = page.locator('app-audio-player button[aria-label="Toggle shuffle"]');
    await expect(btn).not.toHaveClass(/active/);
    await btn.click();
    await expect(btn).toHaveClass(/active/);
    await btn.click();
    await expect(btn).not.toHaveClass(/active/);
  });

  test('repeat button cycles OFF → ALL → ONE → OFF', async ({ page }) => {
    const btn = page.locator('app-audio-player button[aria-label="Cycle repeat mode"]');
    // OFF (initial): icon is "repeat", title attribute reflects mode.
    await expect(btn).toHaveAttribute('title', 'Repeat: OFF');
    await btn.click();
    await expect(btn).toHaveAttribute('title', 'Repeat: ALL');
    await btn.click();
    await expect(btn).toHaveAttribute('title', 'Repeat: ONE');
    await btn.click();
    await expect(btn).toHaveAttribute('title', 'Repeat: OFF');
  });

  test('scrubber seeks the audio element', async ({ page }) => {
    // Wait until duration is known (audio element fires loadedmetadata
    // and reports actual ~30 s); then nudge the scrubber to ~10 s.
    await page.waitForFunction(() => {
      const a = document.querySelector('app-audio-player audio') as HTMLAudioElement | null;
      return !!a && isFinite(a.duration) && a.duration > 1;
    }, undefined, { timeout: 5_000 });

    const slider = page.locator('app-audio-player .scrubber input[type="range"]');
    await slider.evaluate((el: HTMLInputElement) => {
      el.value = '10';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });

    // The seek effect lives on the audio element; assert currentTime
    // landed near the requested value (small drift OK on keyframe
    // boundaries, though for mp3 it's basically exact).
    await page.waitForFunction(() => {
      const a = document.querySelector('app-audio-player audio') as HTMLAudioElement;
      return Math.abs(a.currentTime - 10) < 1.5;
    }, undefined, { timeout: 3_000 });
  });

  test('Close player button hides the bar and clears the queue', async ({ page }) => {
    const bar = page.locator('app-audio-player .audio-player');
    await expect(bar).toHaveClass(/visible/);
    await page.locator('app-audio-player button[aria-label="Close audio player"]').click();
    // .visible is driven by queue.hasQueue(); clearing the queue
    // flips it false and the now-playing block + transport disappear.
    await expect(bar).not.toHaveClass(/visible/);
    await expect(page.locator('app-audio-player .now-playing')).toHaveCount(0);
  });
});
