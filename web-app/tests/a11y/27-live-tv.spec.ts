import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Live TV grid + player tests.
//
// /live-tv (LiveTvComponent)
//   Reads /api/v2/catalog/live-tv/channels and renders one card per
//   channel. Click → routes.live-tv-player(channelId) with the
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
    await page.waitForSelector('app-live-tv .channel-grid');
  });

  test('renders one card per channel with number / name / affiliation', async ({ page }) => {
    const cards = page.locator('app-live-tv .channel-card');
    await expect(cards).toHaveCount(3);

    // Card 1: 5.1 WTTW HD — PBS
    const first = cards.first();
    await expect(first.locator('.channel-number')).toContainText('5.1');
    await expect(first.locator('.channel-name')).toContainText('WTTW HD');
    await expect(first.locator('.channel-affiliation')).toContainText('PBS');

    // Card 3: 9.1 WGN HD — Independent
    const third = cards.nth(2);
    await expect(third.locator('.channel-number')).toContainText('9.1');
    await expect(third.locator('.channel-name')).toContainText('WGN HD');
    await expect(third.locator('.channel-affiliation')).toContainText('Independent');
  });

  test('total label reflects channel count', async ({ page }) => {
    await expect(page.locator('app-live-tv .status-label')).toContainText('3 channels');
  });

  test('clicking a card routes to /live-tv/:id with ?name=...', async ({ page }) => {
    await page.locator('app-live-tv .channel-card').nth(1).click();
    // Channel 2 → /live-tv/2?name=7.1+WLS+HD (URL-encoded space).
    await expect(page).toHaveURL(/\/live-tv\/2\?name=7\.1(\+|%20)WLS(\+|%20)HD/);
  });

  test('empty channel list shows the empty message', async ({ page }) => {
    await page.route('**/api/v2/catalog/live-tv/channels', route =>
      route.fulfill({ json: { channels: [], total: 0 } }),
    );
    await page.goto('/live-tv');
    await expect(page.locator('app-live-tv .empty-message'))
      .toContainText('No channels available');
  });

  test('403 from channels endpoint shows the not-available message', async ({ page }) => {
    await page.route('**/api/v2/catalog/live-tv/channels', route =>
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
    await expect(page).toHaveURL(/\/live-tv$/);
  });

  test('Escape key navigates back to /live-tv', async ({ page }) => {
    // The container has tabindex=0 and the (keydown.escape) binding
    // routes back. Focus the container first so the keydown lands on it.
    await page.locator('app-live-tv-player .player-container').focus();
    await page.keyboard.press('Escape');
    await expect(page).toHaveURL(/\/live-tv$/);
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
