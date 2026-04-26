import { test, expect } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// Resume-prompt integration test. Layered on the same fixture set as
// 15-player-playback (20s.mp4 etc.); the additional behaviour under
// test is the prompt that appears when the saved playback position
// for this transcode is > 10 s.
//
// Two paths through the prompt:
//   - "Resume": player picks up at the saved position and plays.
//   - "Start Over": progress is cleared server-side and playback
//     starts at 0.
//
// The mock-backend default for /playback-progress/* returns
// { position: 0 } — that's why the playback spec never sees the
// prompt. Each test here overrides it LIFO with a non-zero
// position so the prompt opens, then asserts the rest.
const FIXTURE_DIR = 'tests/fixtures/player';
const TRANSCODE_ID = 42;
const RESUME_AT = 12;  // seconds; must clear the player's > 10 gate.

async function setupPlayerRoutes(page: import('@playwright/test').Page) {
  await page.route(`**/stream/${TRANSCODE_ID}/chapters.json`, route =>
    route.fulfill({ path: `${FIXTURE_DIR}/chapters.json`, headers: { 'Content-Type': 'application/json' } })
  );
  await page.route(`**/stream/${TRANSCODE_ID}/thumbs.vtt`, route =>
    route.fulfill({ path: `${FIXTURE_DIR}/thumbs.vtt`, headers: { 'Content-Type': 'text/vtt' } })
  );
  await page.route(`**/stream/${TRANSCODE_ID}/subs.vtt`, route => {
    if (route.request().method() === 'HEAD') {
      return route.fulfill({ status: 200, headers: { 'Content-Type': 'text/vtt' }, body: '' });
    }
    return route.fulfill({ path: `${FIXTURE_DIR}/subs.vtt`, headers: { 'Content-Type': 'text/vtt' } });
  });
  await page.route(`**/stream/${TRANSCODE_ID}`, route =>
    route.fulfill({
      path: `${FIXTURE_DIR}/20s.mp4`,
      headers: { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes' },
    })
  );
}

test.describe('player resume prompt', () => {
  test('Resume picks playback up at the saved position', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await setupPlayerRoutes(page);

    // Override the catch-all GET on /playback-progress/* to return a
    // saved position past the > 10 s gate. POST/DELETE keep falling
    // through to the mockBackend handler so reportProgress() and
    // reportClear() succeed silently.
    await page.route(`**/playback-progress/${TRANSCODE_ID}`, route => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ json: { position: RESUME_AT, duration: 20 } });
      }
      return route.fallback();
    });

    await page.goto(`/play/${TRANSCODE_ID}`);

    // Prompt renders BEFORE the video element (startPlayback isn't
    // called until the user picks an option), so wait for the prompt
    // to appear before clicking through.
    const prompt = page.locator('.resume-overlay');
    await expect(prompt).toBeVisible({ timeout: 5_000 });
    await expect(prompt).toContainText('Resume from');

    await prompt.locator('button', { hasText: 'Resume' }).click();
    await expect(prompt).not.toBeVisible({ timeout: 2_000 });

    // Video appears, seeks past the resume point, and plays.
    // The seek tolerance covers HTMLMediaElement's keyframe snap —
    // the seeked time may land slightly before RESUME_AT but not by
    // more than a couple of seconds. > RESUME_AT - 2 is the safe
    // lower bound; we also assert it's still well under the clip
    // length so we know we didn't accidentally skip to the end.
    await page.waitForFunction(
      ([floor]) => {
        const v = document.querySelector('video') as HTMLVideoElement | null;
        return !!v && v.currentTime > floor && !v.paused && v.duration > 0;
      },
      [RESUME_AT - 2],
      { timeout: 10_000 },
    );

    const finalTime = await page.evaluate(
      () => (document.querySelector('video') as HTMLVideoElement).currentTime,
    );
    expect(finalTime).toBeGreaterThan(RESUME_AT - 2);
    expect(finalTime).toBeLessThan(20);
  });

  test('Start Over begins playback at 0 and clears the saved progress', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await setupPlayerRoutes(page);

    await page.route(`**/playback-progress/${TRANSCODE_ID}`, route => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ json: { position: RESUME_AT, duration: 20 } });
      }
      return route.fallback();
    });

    // Capture the DELETE so we can assert it was sent — that's how
    // the player clears the saved position before restarting.
    const cleared = page.waitForRequest(req =>
      req.method() === 'DELETE' && req.url().includes(`/playback-progress/${TRANSCODE_ID}`),
    );

    await page.goto(`/play/${TRANSCODE_ID}`);

    const prompt = page.locator('.resume-overlay');
    await expect(prompt).toBeVisible({ timeout: 5_000 });
    await prompt.locator('button', { hasText: 'Start Over' }).click();
    await expect(prompt).not.toBeVisible({ timeout: 2_000 });

    await cleared;

    // Plays from the beginning. Catching this without a race: wait
    // until currentTime advances past 1 s, then assert it isn't
    // anywhere near the resume position (which would mean Start
    // Over silently honoured the saved point).
    await page.waitForFunction(() => {
      const v = document.querySelector('video') as HTMLVideoElement | null;
      return !!v && v.currentTime > 1 && !v.paused;
    }, undefined, { timeout: 10_000 });

    const t = await page.evaluate(
      () => (document.querySelector('video') as HTMLVideoElement).currentTime,
    );
    // Generous upper bound — the test takes a beat to set up, so
    // currentTime can be a few seconds in by the time we sample.
    // RESUME_AT - 4 = 8 keeps a clear gap from the "would've
    // resumed" line at 12.
    expect(t).toBeLessThan(RESUME_AT - 4);
  });
});
