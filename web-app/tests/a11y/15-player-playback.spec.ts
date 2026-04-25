import { test, expect } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// Player integration test — exercises the actual <video> element
// rather than just the chrome around it. Three things under test:
//   1. The video plays (currentTime advances past a chosen mark).
//   2. Subtitles render in the custom overlay (.custom-subs span)
//      when an active VTT cue's window is hit.
//   3. Hovering the seek bar surfaces the thumbnail preview with
//      the right sprite-sheet src and offset.
//
// All three rely on the small ffmpeg-generated fixtures under
// tests/fixtures/player/. The fixture transcode id is 42; nothing
// else in the test infrastructure cares which id.
//
// Fixture files (~115 KB total committed):
//   5s.mp4      — 5 s of testsrc + 440 Hz tone, H.264/AAC, 320x240
//   thumbs.jpg  — 5×1 sprite of one-second-cadence frames at 160x90
//   thumbs.vtt  — five cues mapping each second to one sprite cell
//   subs.vtt    — three cues at known timestamps inside the 5 s window
//   chapters.json — two chapters, no skip segments
const FIXTURE_DIR = 'tests/fixtures/player';
const TRANSCODE_ID = 42;

test.describe('player integration', () => {
  test('plays video, displays subtitles, shows scrub preview', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);

    // Override the catch-all /stream/* mock with real fixture bytes.
    // Playwright's LIFO route matching means later registrations win.
    // The video element URL is bare /stream/{id} (no extension) per
    // PlayerComponent.startPlayback().
    await page.route(`**/stream/${TRANSCODE_ID}/chapters.json`, route =>
      route.fulfill({ path: `${FIXTURE_DIR}/chapters.json`, headers: { 'Content-Type': 'application/json' } })
    );
    await page.route(`**/stream/${TRANSCODE_ID}/thumbs.vtt`, route =>
      route.fulfill({ path: `${FIXTURE_DIR}/thumbs.vtt`, headers: { 'Content-Type': 'text/vtt' } })
    );
    await page.route(`**/stream/${TRANSCODE_ID}/subs.vtt`, route => {
      // HEAD probe (player discovery) needs an empty-body 200 with
      // a Content-Type header; GET (the <track> element load) needs
      // the actual VTT bytes. Fulfilling HEAD with a file body
      // breaks the Angular HttpClient's response parsing and the
      // promise rejects, leaving subsAvailable false → no track
      // ever gets appended.
      if (route.request().method() === 'HEAD') {
        return route.fulfill({ status: 200, headers: { 'Content-Type': 'text/vtt' }, body: '' });
      }
      return route.fulfill({ path: `${FIXTURE_DIR}/subs.vtt`, headers: { 'Content-Type': 'text/vtt' } });
    });
    await page.route(`**/stream/${TRANSCODE_ID}/thumbs.jpg`, route =>
      route.fulfill({ path: `${FIXTURE_DIR}/thumbs.jpg`, headers: { 'Content-Type': 'image/jpeg' } })
    );
    await page.route(`**/stream/${TRANSCODE_ID}`, route =>
      route.fulfill({
        path: `${FIXTURE_DIR}/5s.mp4`,
        headers: {
          'Content-Type': 'video/mp4',
          // <video> tolerates getting the full body with 200 even
          // though it sends a Range header for seeking; for a 100 KB
          // clip it just GETs once. Accept-Ranges signals capability
          // so the seek logic doesn't disable scrubbing.
          'Accept-Ranges': 'bytes',
        },
      })
    );

    await page.goto(`/play/${TRANSCODE_ID}`);
    await page.waitForSelector('app-player video');

    // ---- 1. video plays ----
    // Wait for currentTime to clear the first subtitle window AND a
    // bit of buffer so the cuechange has time to fire deterministically
    // before the assertion below. 1.6 s lands inside the gap between
    // cue 1 (ends 1.5 s) and cue 2 (starts 2.0 s) — we'll then poll
    // forward into cue 2 in the next step.
    await page.waitForFunction(() => {
      const v = document.querySelector('video') as HTMLVideoElement | null;
      return !!v && v.currentTime >= 1.6 && v.duration > 0 && !v.paused;
    }, undefined, { timeout: 10_000 });

    // ---- 2. subtitles display ----
    // subsEnabled defaults to true and the track is added with
    // default=true, so the overlay populates without user interaction
    // once a cue becomes active. Wait until the playhead lands inside
    // cue 2 (2.0 - 3.0 s) and confirm the overlay text matches.
    await expect(page.locator('.custom-subs span')).toContainText(
      'Cue at two seconds',
      { timeout: 10_000 },
    );

    // ---- 3. scrub preview ----
    // Compute the hover x-coordinate from the actual video duration
    // — ffmpeg's `testsrc duration=5` produces a clip slightly under
    // 5 s, so a hardcoded fraction lands in the wrong sprite cell.
    // Targeting t = 2.5 always falls inside the third cell
    // (range 2.0 - 3.0, x=320) regardless of small duration drift.
    const duration = await page.evaluate(
      () => (document.querySelector('video') as HTMLVideoElement).duration,
    );
    expect(duration).toBeGreaterThan(2.5);
    const targetFraction = 2.5 / duration;

    const seekBar = page.locator('.seek-bar-container');
    const box = await seekBar.boundingBox();
    expect(box).not.toBeNull();
    await page.mouse.move(box!.x + box!.width * targetFraction, box!.y + box!.height / 2);

    const thumbPreview = page.locator('.thumb-preview');
    await expect(thumbPreview).toHaveClass(/visible/, { timeout: 2_000 });
    const sprite = page.locator('.thumb-sprite');
    await expect(sprite).toHaveAttribute('src', new RegExp(`/stream/${TRANSCODE_ID}/thumbs\\.jpg$`));
    // Third sprite cell sits at x=320; the player styles the img
    // with `left: -320px` to expose it through the 160-wide window.
    await expect(sprite).toHaveAttribute('style', /left:\s*-320px/);
  });
});
