import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto } from '../helpers/proto-fixture';
import { create } from '@bufbuild/protobuf';
import { TvChannelListResponseSchema } from '../../src/app/proto-gen/live_pb';
import { expectView } from '../helpers/expect-view';

const LS = '/mediamanager.LiveService';

// Live TV grid + player tests.
//
// /live-tv (LiveTvComponent)
//   Opens with the live-top block (player box with the first channel's
//   tile, info column with h1 / h2 / meta and a Watch Live pill).
//   Reads LiveService/ListTvChannels and renders the guide: group
//   chips (aria-pressed) and one guide row per channel. Choosing a
//   row → routes.live-tv-player(channelId) with the
//   channel's "<num> <name>" as the ?name query.
//
// /live-tv/:channelId (LiveTvPlayerComponent)
//   Loads HLS via hls.js from /live-tv-stream/:channelId/stream.m3u8.
//   The actual stream load fails in this hermetic test (no real
//   transcoder), but the player chrome — top bar, back button,
//   fullscreen toggle, status overlay — is fully testable.

// Headless Chromium can't actually transition into the OS-level
// fullscreen state without a real user gesture chain; replace the
// fullscreen API with bookkeeping-only stubs so the toggle's signal
// effects fire without real DOM transitions. The test asserts on the
// component-side state (signal, aria-label) which is the real user-
// visible signal for "I am in fullscreen."
async function stubFullscreenAPI(page: Page) {
  await page.addInitScript(() => {
    Object.defineProperty(Element.prototype, 'requestFullscreen', {
      configurable: true,
      value() {
        Object.defineProperty(document, 'fullscreenElement', {
          configurable: true, get: () => this,
        });
        return Promise.resolve();
      },
    });
    Object.defineProperty(Document.prototype, 'exitFullscreen', {
      configurable: true,
      value() {
        Object.defineProperty(document, 'fullscreenElement', {
          configurable: true, get: () => null,
        });
        return Promise.resolve();
      },
    });
  });
}

test.describe('live TV — grid view', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/live-tv');
    await page.waitForSelector('app-live-tv .guide');
  });

  test('guide renders one row per channel with tile, name / affiliation and Live now', async ({ page }) => {
    const rows = page.locator('app-live-tv .guide .guide-row');
    await expect(rows).toHaveCount(3);

    // Row 1: 5.1 WTTW HD — PBS
    const first = rows.first();
    await expect(first.locator('.guide-ch')).toContainText('WTTW HD');
    await expect(first.locator('.guide-ch small')).toHaveText('5.1');
    await expect(first.locator('.guide-prog .t')).toHaveText('WTTW HD · PBS');
    await expect(first.locator('.guide-prog .m')).toHaveText('Live now · Channel 5.1');
    await expect(first.locator('.badge')).toHaveText(/live/i);

    // Row 3: 9.1 WGN HD — Independent
    const third = rows.nth(2);
    await expect(third.locator('.guide-ch small')).toHaveText('9.1');
    await expect(third.locator('.guide-prog .t')).toHaveText('WGN HD · Independent');
  });

  test('the channel shown in live-top is marked in the guide', async ({ page }) => {
    const tiles = page.locator('app-live-tv .guide-ch');
    await expect(tiles.nth(0)).toHaveClass(/\bon\b/);
    await expect(tiles.nth(1)).not.toHaveClass(/\bon\b/);
  });

  test('group chips start on All Channels and filter the guide', async ({ page }) => {
    const chips = page.locator('app-live-tv .guide-tabs button.chip');
    await expect(chips).toHaveText(['All Channels', 'PBS', 'ABC', 'Independent']);
    await expect(chips.first()).toHaveAttribute('aria-pressed', 'true');
    await expect(chips.nth(2)).toHaveAttribute('aria-pressed', 'false');

    await chips.nth(2).click();
    await expect(chips.nth(2)).toHaveAttribute('aria-pressed', 'true');
    await expect(chips.first()).toHaveAttribute('aria-pressed', 'false');
    const rows = page.locator('app-live-tv .guide .guide-row');
    await expect(rows).toHaveCount(1);
    await expect(rows.first().locator('.guide-prog .t')).toHaveText('WLS HD · ABC');

    await chips.nth(3).click();
    await expect(rows).toHaveCount(1);
    await expect(rows.first().locator('.guide-prog .t')).toHaveText('WGN HD · Independent');

    await chips.first().click();
    await expect(rows).toHaveCount(3);
  });

  test('live-top shows the first channel in the player box and info column', async ({ page }) => {
    const top = page.locator('app-live-tv .live-top');
    await expect(top).toBeVisible();
    await expect(top.locator('.live-player .channel-tile')).toContainText('WTTW HD');
    await expect(top.locator('.live-player .badge')).toHaveText(/live/i);
    await expect(top.getByRole('heading', { level: 1 })).toHaveText('WTTW HD');
    await expect(top.getByRole('heading', { level: 2 })).toHaveText('PBS');
    await expect(top.locator('.meta')).toContainText('Channel 5.1');
  });

  test('Watch Live routes to the player for the shown channel', async ({ page }) => {
    await page.locator('app-live-tv .live-top').getByRole('link', { name: 'Watch Live' }).click();
    await expectView(page, /\/live-tv\/1\?name=5\.1(\+|%20)WTTW(\+|%20)HD/, 'app-live-tv-player');
  });

  test('player box and info column sit side by side at 1440 and stack at 390', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const player = page.locator('app-live-tv .live-player');
    const info = page.locator('app-live-tv .live-info');
    let p = await player.boundingBox();
    let i = await info.boundingBox();
    expect(p!.width).toBeLessThanOrEqual(604.5);
    expect(i!.x).toBeGreaterThan(p!.x + p!.width);

    await page.setViewportSize({ width: 390, height: 844 });
    await expect.poll(async () => {
      p = await player.boundingBox();
      i = await info.boundingBox();
      return i!.y >= p!.y + p!.height;
    }).toBe(true);
  });

  test('choosing a guide row routes to /live-tv/:id with ?name=...', async ({ page }) => {
    await page.locator('app-live-tv .guide-row').nth(1).click();
    // Channel 2 → /live-tv/2?name=7.1+WLS+HD (URL-encoded space).
    await expectView(page, /\/live-tv\/2\?name=7\.1(\+|%20)WLS(\+|%20)HD/, 'app-live-tv-player');
  });

  test('empty channel list shows the empty message', async ({ page }) => {
    await page.route(`**${LS}/ListTvChannels`, r =>
      fulfillProto(r, TvChannelListResponseSchema, create(TvChannelListResponseSchema)));
    await page.goto('/live-tv');
    await expect(page.locator('app-live-tv .empty-message'))
      .toContainText('No channels available');
    await expect(page.locator('app-live-tv .live-top')).toHaveCount(0);
  });

  test('403 from channels endpoint shows the not-available message', async ({ page }) => {
    await page.route(`**${LS}/ListTvChannels`, route =>
      route.fulfill({ status: 403 }),
    );
    await page.goto('/live-tv');
    await expect(page.locator('app-live-tv .error-message'))
      .toContainText('Live TV is not available for your account');
  });
});

test.describe('live TV — player view', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await stubFullscreenAPI(page);

    // The player resolves /live-tv-stream/:id/stream.m3u8 via hls.js;
    // mockBackend's matching stub is on the wrong path
    // (/api/v2/live-tv/stream/), so register the right one explicitly
    // here. Empty manifest is fine — hls.js will fail downstream but
    // by then we've already exercised the chrome under test.
    await page.route('**/live-tv-stream/**', route =>
      route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'application/vnd.apple.mpegurl' },
        body: '#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:0\n#EXT-X-ENDLIST\n',
      }),
    );

    await page.goto('/live-tv/2?name=7.1+WLS+HD');
    await page.waitForSelector('app-live-tv-player .player-container');
  });

  test('top bar renders the channel name from the ?name query', async ({ page }) => {
    await expect(page.locator('app-live-tv-player .channel-label')).toContainText('7.1 WLS HD');
  });

  test('Back button navigates to /live-tv', async ({ page }) => {
    await page.locator('app-live-tv-player button[aria-label="Back to channel guide"]').click();
    await expectView(page, /\/live-tv$/, 'app-live-tv');
  });

  test('Escape key navigates back to /live-tv', async ({ page }) => {
    // The container has tabindex=0 and the (keydown.escape) binding
    // routes back. Focus the container first so the keydown lands on it.
    await page.locator('app-live-tv-player .player-container').focus();
    await page.keyboard.press('Escape');
    await expectView(page, /\/live-tv$/, 'app-live-tv');
  });

  test('fullscreen toggle flips state via the stubbed API', async ({ page }) => {
    const btn = page.locator('app-live-tv-player button[aria-label="Enter fullscreen"]');
    await expect(btn).toBeVisible();
    await btn.click();
    // Component's isFullscreen() signal flips true → aria-label
    // re-binds to "Exit fullscreen". The icon swaps too but
    // aria-label is the stable assertion.
    await expect(page.locator('app-live-tv-player button[aria-label="Exit fullscreen"]'))
      .toBeVisible();

    // Toggle back. document.fullscreenElement is the stub's bookkeeping;
    // the component checks it before deciding to enter vs exit.
    await page.locator('app-live-tv-player button[aria-label="Exit fullscreen"]').click();
    await expect(page.locator('app-live-tv-player button[aria-label="Enter fullscreen"]'))
      .toBeVisible();
  });

  test('status overlay surfaces an error + retry path when stream fails', async ({ page }) => {
    // The empty m3u8 we serve takes hls.js straight from MANIFEST_PARSED
    // into a fatal error (no segments to buffer), so the overlay
    // settles on the error branch. That's actually the more useful
    // path to assert — it proves the user-visible failure UI works.
    const overlay = page.locator('app-live-tv-player .status-overlay');
    await expect(overlay).toBeVisible();
    await expect(overlay.locator('.status-text'))
      .toContainText(/Channel unavailable|No signal|Stream error|tuners are busy/, { timeout: 5_000 });
    await expect(overlay.locator('button.retry-btn')).toBeVisible();
  });
});
