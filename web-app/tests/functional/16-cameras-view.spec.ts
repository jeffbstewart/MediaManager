import { test, expect } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { loadFixture } from '../helpers/load-fixture';

// Serial mode for the same reason as 15-books-and-reader: the
// fullscreen view holds an open MJPEG-style image stream and the
// snapshot mode keeps a 3-second refresh interval running, both of
// which keep ng serve busier than the default fullyParallel
// configuration handles cleanly. Serializing within this file keeps
// every assertion hermetic.
test.describe.configure({ mode: 'serial' });

// Cameras grid + fullscreen overlay test (the non-admin view).
//
// The cameras page lives at /cameras and renders one tile per camera
// returned by /api/v2/catalog/cameras. There are three modes worth
// covering:
//   - Snapshot mode (default)  → img src = /cam/:id/snapshot.jpg?t=...
//   - Live (MJPEG) mode        → img src = /cam/:id/mjpeg
//   - Fullscreen overlay       → click any tile, MJPEG-streamed
//                                 single-camera view
//
// The "single camera view" is the fullscreen overlay on this page —
// no separate route. Admin views (/admin/cameras) are out of scope
// for this spec; they're covered by axe/17-admin-tier-d.

test.describe('cameras view', () => {

  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);  // covers /cam/** with the 1x1 PNG stub
    await page.goto('/cameras');
  });

  test('grid renders one tile per camera with name + image', async ({ page }) => {
    await page.waitForSelector('app-cameras .camera-grid');
    const cells = page.locator('app-cameras .camera-cell');
    await expect(cells).toHaveCount(3);

    const names = ['Front Door', 'Driveway', 'Back Yard'];
    for (let i = 0; i < names.length; i++) {
      await expect(cells.nth(i).locator('.camera-label')).toContainText(names[i]);
      await expect(cells.nth(i).locator('img.camera-img')).toBeVisible();
    }
  });

  test('default mode is Snapshot — img src points at /cam/:id/snapshot.jpg', async ({ page }) => {
    await page.waitForSelector('app-cameras .camera-grid');
    // Mode toggle button shows "Live" when in snapshot mode (i.e. it
    // offers the action that *would* be taken on click).
    await expect(page.locator('app-cameras button.mode-toggle')).toContainText('Live');

    const firstImg = page.locator('app-cameras .camera-cell').first().locator('img');
    await expect(firstImg).toHaveAttribute('src', /\/cam\/1\/snapshot\.jpg\?t=\d+/);
  });

  test('toggling to Live switches img src to /cam/:id/mjpeg', async ({ page }) => {
    await page.waitForSelector('app-cameras .camera-grid');
    await page.locator('app-cameras button.mode-toggle').click();

    // Button label flips to "Snapshots" once we're in live mode.
    await expect(page.locator('app-cameras button.mode-toggle')).toContainText('Snapshots');

    const firstImg = page.locator('app-cameras .camera-cell').first().locator('img');
    await expect(firstImg).toHaveAttribute('src', /\/cam\/1\/mjpeg$/);
  });

  test('toggling back to Snapshot restores the snapshot URL', async ({ page }) => {
    await page.waitForSelector('app-cameras .camera-grid');
    await page.locator('app-cameras button.mode-toggle').click();
    await page.locator('app-cameras button.mode-toggle').click();
    const firstImg = page.locator('app-cameras .camera-cell').first().locator('img');
    await expect(firstImg).toHaveAttribute('src', /\/cam\/1\/snapshot\.jpg\?t=\d+/);
    await expect(page.locator('app-cameras button.mode-toggle')).toContainText('Live');
  });

  test('clicking a camera opens the fullscreen overlay with its name', async ({ page }) => {
    await page.waitForSelector('app-cameras .camera-grid');
    await page.locator('app-cameras .camera-cell').first().click();

    const overlay = page.locator('app-cameras .fullscreen-overlay');
    await expect(overlay).toBeVisible();
    await expect(overlay.locator('.fullscreen-header')).toContainText('Front Door');
    // Fullscreen is always MJPEG (live), regardless of grid mode.
    await expect(overlay.locator('img.fullscreen-img'))
      .toHaveAttribute('src', /\/cam\/1\/mjpeg$/);
  });

  test('fullscreen close button hides the overlay', async ({ page }) => {
    await page.waitForSelector('app-cameras .camera-grid');
    await page.locator('app-cameras .camera-cell').nth(1).click();
    const overlay = page.locator('app-cameras .fullscreen-overlay');
    await expect(overlay).toBeVisible();
    // The fullscreen img is laid out over the entire content area
    // and Playwright's actionability check sees it as intercepting
    // pointer events on the close button. dispatchEvent fires the
    // click directly on the button, bypassing both the actionability
    // wait and the synthetic-mouse-pointer path that hits the img.
    await overlay.locator('button.close-btn').dispatchEvent('click');
    await expect(overlay).toHaveCount(0);
  });

  test('clicking the overlay backdrop also closes', async ({ page }) => {
    await page.waitForSelector('app-cameras .camera-grid');
    await page.locator('app-cameras .camera-cell').first().click();
    const overlay = page.locator('app-cameras .fullscreen-overlay');
    await expect(overlay).toBeVisible();
    // Clicking the .fullscreen-overlay (NOT the inner .fullscreen-
    // content) triggers closeFullscreen via the (click) handler;
    // the inner content $event.stopPropagation()s. The fullscreen
    // image expands to cover the corners too, so dispatch a click
    // event directly on the overlay element instead of relying on
    // pointer-coordinates (which would land on the img).
    await overlay.dispatchEvent('click');
    await expect(overlay).toHaveCount(0);
  });

  test('opening a different camera updates the overlay name + src', async ({ page }) => {
    await page.waitForSelector('app-cameras .camera-grid');
    // Open #1, close, open #3 — verify state isn't sticky.
    await page.locator('app-cameras .camera-cell').first().click();
    const overlay = page.locator('app-cameras .fullscreen-overlay');
    await expect(overlay.locator('.fullscreen-header')).toContainText('Front Door');
    // The fullscreen img is laid out over the entire content area
    // and Playwright's actionability check sees it as intercepting
    // pointer events on the close button. dispatchEvent fires the
    // click directly on the button, bypassing both the actionability
    // wait and the synthetic-mouse-pointer path that hits the img.
    await overlay.locator('button.close-btn').dispatchEvent('click');
    // Wait for the overlay to actually disappear before clicking the
    // next tile — otherwise the still-mounted fullscreen img sits
    // over the camera grid and intercepts the click.
    await expect(overlay).toHaveCount(0);

    await page.locator('app-cameras .camera-cell').nth(2).click();
    await expect(page.locator('app-cameras .fullscreen-overlay .fullscreen-header'))
      .toContainText('Back Yard');
    await expect(page.locator('app-cameras .fullscreen-overlay img.fullscreen-img'))
      .toHaveAttribute('src', /\/cam\/3\/mjpeg$/);
  });

  test('empty cameras list shows the empty message', async ({ page }) => {
    // Replace the cameras endpoint with an empty list. Fresh nav so
    // ngOnInit re-runs against the override.
    await page.route('**/api/v2/catalog/cameras', route =>
      route.fulfill({ json: { cameras: [], total: 0 } })
    );
    await page.goto('/cameras');
    await expect(page.locator('app-cameras .empty-message'))
      .toContainText('No cameras configured');
    await expect(page.locator('app-cameras .camera-grid')).toHaveCount(0);
  });

  test('cameras endpoint failure shows the error message', async ({ page }) => {
    await page.route('**/api/v2/catalog/cameras', route =>
      route.fulfill({ status: 500 })
    );
    await page.goto('/cameras');
    await expect(page.locator('app-cameras .error-message'))
      .toContainText('Failed to load cameras');
  });
});
