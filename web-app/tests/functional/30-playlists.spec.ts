import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Playlists tests.
//
// /content/playlists (PlaylistsComponent)
//   Browse / Mine scope chips, "Shuffle library" + "New playlist"
//   actions, and a grid of <a class="playlist-card"> tiles. Smart
//   playlists ride above on the All view.
//
// /playlist/:id (PlaylistDetailComponent)
//   Hero image (must match the list-card hero), play / duplicate /
//   rename / edit-description / private toggle / delete actions,
//   bulk-select track removal, drag-drop reorder. Up/down arrow
//   buttons were removed in favour of drag-drop only.
//
// Most mutations use window.prompt / window.confirm — Playwright
// catches those via page.on('dialog', ...). Stub each
// /api/v2/playlists/* mutation with a 204 so the catalog service's
// awaited promises resolve, then assert via waitForRequest.

// Stub all the playlist mutation endpoints with 204 + minimal JSON.
// Registered AFTER mockBackend so they win Playwright's LIFO match.
async function stubPlaylistMutations(page: Page) {
  await page.route('**/api/v2/playlists', route => {
    if (route.request().method() === 'POST') {
      // create — return the new id so the page can navigate.
      return route.fulfill({ json: { id: 99, name: 'Created' } });
    }
    return route.fallback();
  });
  await page.route('**/api/v2/playlists/library-shuffle', route =>
    route.fulfill({ json: { tracks: [] } }),
  );
  await page.route('**/api/v2/playlists/*/rename', route =>
    route.fulfill({ status: 204 }),
  );
  await page.route('**/api/v2/playlists/*/privacy', route =>
    route.fulfill({ status: 204 }),
  );
  await page.route('**/api/v2/playlists/*/reorder', route =>
    route.fulfill({ status: 204 }),
  );
  await page.route('**/api/v2/playlists/*/duplicate', route =>
    route.fulfill({ json: { id: 42, name: 'Road Trip (copy)' } }),
  );
  await page.route('**/api/v2/playlists/*/tracks/*', route => {
    if (route.request().method() === 'DELETE') return route.fulfill({ status: 204 });
    return route.fallback();
  });
  await page.route('**/api/v2/playlists/*', route => {
    if (route.request().method() === 'DELETE') return route.fulfill({ status: 204 });
    return route.fallback();
  });
}

test.describe('playlists — list view', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await stubPlaylistMutations(page);
    await page.goto('/content/playlists');
    await page.waitForSelector('app-playlists .playlist-grid');
  });

  test('renders playlist cards + smart-playlist section', async ({ page }) => {
    // Two grids on All scope: smart-section first, then user playlists.
    await expect(page.locator('app-playlists .smart-section .playlist-card')).toHaveCount(2);
    await expect(page.locator('app-playlists .playlist-grid').nth(1).locator('.playlist-card'))
      .toHaveCount(3);
    // Card 0 of the user grid is "Road Trip" with hero, name, owner pill.
    const first = page.locator('app-playlists .playlist-grid').nth(1).locator('.playlist-card').first();
    await expect(first.locator('.name')).toContainText('Road Trip');
    await expect(first.locator('.owner-pill')).toContainText('you');
    await expect(first.locator('img')).toHaveAttribute('src', /\/posters\/w185\/301$/);
  });

  test('placeholder renders when hero_poster_url is null', async ({ page }) => {
    // Coffee Shop (id=2) has hero_poster_url=null.
    const card = page.locator('app-playlists .playlist-grid').nth(1).locator('.playlist-card').nth(1);
    await expect(card.locator('.name')).toContainText('Coffee Shop');
    await expect(card.locator('img')).toHaveCount(0);
    await expect(card.locator('.hero-placeholder mat-icon')).toContainText('queue_music');
  });

  test('All / Mine scope chips fire requests at the right endpoint', async ({ page }) => {
    // Default scope is "all" — confirmed by initial /api/v2/playlists hit
    // during beforeEach. Now toggle to Mine and assert the /mine endpoint.
    const minePromise = page.waitForRequest(req =>
      req.url().endsWith('/api/v2/playlists/mine'),
      { timeout: 3_000 },
    );
    await page.locator('app-playlists mat-chip', { hasText: 'Mine' }).click();
    await minePromise;
    await expect(page.locator('app-playlists mat-chip', { hasText: 'Mine' }))
      .toHaveClass(/mat-mdc-chip-highlighted/);

    // Smart-playlist section is hidden on Mine scope.
    await expect(page.locator('app-playlists .smart-section')).toHaveCount(0);

    // Back to All — request goes to /api/v2/playlists (no /mine).
    const allPromise = page.waitForRequest(req =>
      /\/api\/v2\/playlists(\?|$)/.test(req.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-playlists mat-chip', { hasText: 'All' }).click();
    await allPromise;
  });

  test('Shuffle library fires GET /library-shuffle', async ({ page }) => {
    const reqPromise = page.waitForRequest(req =>
      req.url().endsWith('/api/v2/playlists/library-shuffle'),
      { timeout: 3_000 },
    );
    await page.locator('app-playlists button', { hasText: 'Shuffle library' }).click();
    await reqPromise;
  });

  test('New playlist prompts for a name then POSTs and navigates', async ({ page }) => {
    page.on('dialog', d => d.accept('Late-Night Jazz'));
    const created = page.waitForRequest(req =>
      req.method() === 'POST' && req.url().endsWith('/api/v2/playlists'),
      { timeout: 3_000 },
    );
    await page.locator('app-playlists button', { hasText: 'New playlist' }).click();
    const req = await created;
    expect(req.postDataJSON()).toEqual({ name: 'Late-Night Jazz', description: null });
    // create stub returns id=99 → component navigates to /playlist/99.
    await expect(page).toHaveURL(/\/playlist\/99$/);
  });

  test('Duplicate on a card POSTs /duplicate and navigates to the fork', async ({ page }) => {
    const dup = page.waitForRequest(req =>
      req.method() === 'POST' && /\/api\/v2\/playlists\/1\/duplicate$/.test(req.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-playlists .playlist-grid').nth(1)
      .locator('.playlist-card').first()
      .locator('button', { hasText: 'Duplicate' }).click();
    await dup;
    // duplicate stub returns id=42 → component navigates to /playlist/42.
    await expect(page).toHaveURL(/\/playlist\/42$/);
  });

  test('clicking a playlist card navigates to /playlist/:id', async ({ page }) => {
    await page.locator('app-playlists .playlist-grid').nth(1)
      .locator('.playlist-card').first().click();
    await expect(page).toHaveURL(/\/playlist\/1$/);
  });
});

test.describe('playlists — detail view', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await stubPlaylistMutations(page);
    await page.goto('/playlist/1');
    await page.waitForSelector('app-playlist-detail .hero-row');
  });

  test('hero image matches the list-card hero (same /posters URL)', async ({ page }) => {
    // Both fixtures intentionally point at /posters/w185/301 — the
    // server contract is "card hero == detail hero".
    await expect(page.locator('app-playlist-detail .hero-image img'))
      .toHaveAttribute('src', /\/posters\/w185\/301$/);
  });

  test('renders track rows with name, album link, position, duration', async ({ page }) => {
    const rows = page.locator('app-playlist-detail ol.tracks li.track');
    await expect(rows).toHaveCount(2);
    await expect(rows.first().locator('.track-name')).toContainText('So What');
    await expect(rows.first().locator('.track-album')).toContainText('Kind of Blue');
    await expect(rows.first().locator('.position')).toContainText('1');
    await expect(rows.first().locator('.duration')).toContainText('9:25');
  });

  test('reorder UI is drag-handles only — no up/down arrow buttons', async ({ page }) => {
    // Drag handles render per row when the user owns the playlist.
    await expect(page.locator('app-playlist-detail mat-icon.drag-handle')).toHaveCount(2);
    // Up/down arrow buttons were removed — drag-drop is the only reorder UI.
    await expect(page.locator('app-playlist-detail button[aria-label="Move up"]')).toHaveCount(0);
    await expect(page.locator('app-playlist-detail button[aria-label="Move down"]')).toHaveCount(0);
  });

  test('Play button kicks off playback', async ({ page }) => {
    // Play queues the playlist into the audio player. The transcode-cap
    // probe fires when playback starts; assert that as the proxy signal
    // that the queue moved into "playing" state. Catch via waitForRequest
    // on /audio/ or the audio-player visible class.
    await page.locator('app-playlist-detail .hero-actions button', { hasText: 'Play' }).click();
    await expect(page.locator('.audio-player.visible')).toBeVisible({ timeout: 3_000 });
  });

  test('Rename POSTs /rename with the new name', async ({ page }) => {
    page.on('dialog', d => d.accept('Road Trip 2026'));
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/playlists/1/rename'),
      { timeout: 3_000 },
    );
    await page.locator('app-playlist-detail .hero-actions button', { hasText: 'Rename' }).click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({ name: 'Road Trip 2026', description: 'For long drives' });
  });

  test('Edit description POSTs /rename with the new description', async ({ page }) => {
    page.on('dialog', d => d.accept('Updated description'));
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/playlists/1/rename'),
      { timeout: 3_000 },
    );
    await page.locator('app-playlist-detail .hero-actions button', { hasText: 'Edit description' }).click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({ name: 'Road Trip', description: 'Updated description' });
  });

  test('Private slide-toggle POSTs /privacy with the inverted flag', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/playlists/1/privacy'),
      { timeout: 3_000 },
    );
    // Click the underlying button; mat-slide-toggle renders a clickable
    // button inside the host element.
    await page.locator('app-playlist-detail mat-slide-toggle').click();
    const got = await req;
    // Fixture is_private=false → toggle posts is_private=true.
    expect(got.postDataJSON()).toEqual({ is_private: true });
  });

  test('Duplicate POSTs /duplicate and navigates', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/playlists/1/duplicate'),
      { timeout: 3_000 },
    );
    await page.locator('app-playlist-detail .hero-actions button', { hasText: 'Duplicate' }).click();
    await req;
    await expect(page).toHaveURL(/\/playlist\/42$/);
  });

  test('Delete confirms then DELETEs and navigates back to list', async ({ page }) => {
    page.on('dialog', d => d.accept());
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && /\/api\/v2\/playlists\/1$/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-playlist-detail .hero-actions button', { hasText: 'Delete' }).click();
    await req;
    await expect(page).toHaveURL(/\/content\/playlists$/);
  });

  test('per-row Remove DELETEs that single track', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && /\/api\/v2\/playlists\/1\/tracks\/100$/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-playlist-detail li.track').first()
      .locator('button[aria-label="Remove"]').click();
    await req;
  });

  test('multi-select + Remove selected POSTs /reorder with survivors only', async ({ page }) => {
    // Select track 100 (the first row). Use the row-select mat-checkbox
    // — click its native input directly to avoid the row click handler.
    await page.locator('app-playlist-detail li.track').first()
      .locator('mat-checkbox.row-select input').check();
    await expect(page.locator('app-playlist-detail .bulk-bar')).toBeVisible();
    await expect(page.locator('app-playlist-detail .bulk-bar')).toContainText('1 selected');

    // Confirm dialog — accept.
    page.on('dialog', d => d.accept());
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/playlists/1/reorder'),
      { timeout: 3_000 },
    );
    await page.locator('app-playlist-detail .bulk-bar button', { hasText: 'Remove selected' }).click();
    const got = await req;
    // Survivor-only order: [101]. (Track 100 was selected and removed.)
    expect(got.postDataJSON()).toEqual({ playlist_track_ids: [101] });
  });

  test('drag-drop reorder POSTs /reorder with the new order', async ({ page }) => {
    // CDK drag-drop responds to pointer events. With a cdkDragHandle
    // present, drag must be initiated FROM the handle — not just any
    // point in the row. Grab the handle of row 0 and drag past row 1
    // with multi-step moves so CDK's drag-distance threshold (~5 px)
    // registers and the drop list reorders.
    const rows = page.locator('app-playlist-detail li.track');
    const handle = await rows.nth(0).locator('mat-icon.drag-handle').boundingBox();
    const dst = await rows.nth(1).boundingBox();
    if (!handle || !dst) throw new Error('drag-handle / row box missing');

    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/playlists/1/reorder'),
      { timeout: 5_000 },
    );

    const srcX = handle.x + handle.width / 2;
    const srcY = handle.y + handle.height / 2;
    await page.mouse.move(srcX, srcY);
    await page.mouse.down();
    // Tiny initial move trips CDK's drag-distance threshold without
    // overshooting; subsequent move target is below the second row's
    // midpoint so moveItemInArray lands the dragged item at index 1.
    await page.mouse.move(srcX, srcY + 8, { steps: 5 });
    await page.mouse.move(dst.x + dst.width / 2, dst.y + dst.height - 4, { steps: 15 });
    await page.mouse.up();

    const got = await req;
    // Original order: [100, 101]. After moving row 0 below row 1: [101, 100].
    expect(got.postDataJSON()).toEqual({ playlist_track_ids: [101, 100] });
  });
});
