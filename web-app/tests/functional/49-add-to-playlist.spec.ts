import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto, unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { create, fromBinary } from '@bufbuild/protobuf';
import {
  AddTracksToPlaylistRequestSchema,
  AddTracksToPlaylistResponseSchema,
  CreatePlaylistRequestSchema,
  ListPlaylistsResponseSchema,
  PlaylistSummarySchema,
} from '../../src/app/proto-gen/playlist_pb';

const PS = '/mediamanager.PlaylistService';

// shared/add-to-playlist — picker that lets the user append a track
// (or whole album) to an owned playlist, or create a new playlist
// inline. Mounted from the title-detail album page (per-track and
// per-album action menus).

async function setup(page: Page) {
  await mockBackend(page);
  await loginAs(page);
  await stubImages(page);
  // mock-backend's defaults already cover ListPlaylists (3 playlists),
  // CreatePlaylist (id=99), and AddTracksToPlaylist (added=1, ids=[42n])
  // with the values these tests assert on.
  await page.goto('/title/301');
  await page.waitForSelector('app-title-detail');
}

async function openTrackPicker(page: Page) {
  // Open the first track row's overflow menu, click "Add to playlist".
  await page.locator('app-title-detail .track-row button[aria-label="More track actions"]')
    .first().click();
  await page.locator('.mat-mdc-menu-panel button', { hasText: 'Add to playlist' }).click();
  await page.waitForSelector('app-add-to-playlist .picker');
}

test.describe('add-to-playlist — picker render', () => {
  test('opens with heading + populated playlist list', async ({ page }) => {
    await setup(page);
    await openTrackPicker(page);
    await expect(page.locator('app-add-to-playlist h2')).toContainText(/Add.*playlist/i);
    // playlists.list.json fixture has 3 playlists; mockBackend serves
    // it for both /playlists and /playlists/mine.
    await expect(page.locator('app-add-to-playlist .picker-list li')).toHaveCount(3);
  });

  test('renders the "Create new playlist" affordance', async ({ page }) => {
    await setup(page);
    await openTrackPicker(page);
    await expect(page.locator('app-add-to-playlist .create-row'))
      .toContainText('Create new playlist');
  });

  test('private playlists render the lock icon', async ({ page }) => {
    await setup(page);
    await openTrackPicker(page);
    // Coffee Shop is is_private:true in the fixture.
    const private_row = page.locator('app-add-to-playlist .picker-row', { hasText: 'Coffee Shop' });
    await expect(private_row.locator('.picker-private')).toBeVisible();
  });

  test('cancel via the close button fires (cancelled) + closes the picker', async ({ page }) => {
    await setup(page);
    await openTrackPicker(page);
    await page.locator('app-add-to-playlist button[aria-label="Close"]').click();
    await expect(page.locator('app-add-to-playlist .picker')).toHaveCount(0);
  });

  test('clicking the overlay outside the picker closes it', async ({ page }) => {
    await setup(page);
    await openTrackPicker(page);
    // The picker-anchor stops propagation, so clicking the outer
    // overlay is what triggers closePicker(). Dispatch the event
    // directly on the overlay element to bypass center-of-element
    // hit testing (which would land on the anchor).
    await page.locator('app-title-detail .playlist-picker-overlay').first()
      .dispatchEvent('click');
    await expect(page.locator('app-add-to-playlist .picker')).toHaveCount(0);
  });
});

test.describe('add-to-playlist — pick existing', () => {
  test('clicking a playlist calls AddTracksToPlaylist with the track id', async ({ page }) => {
    await setup(page);
    await openTrackPicker(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith(`${PS}/AddTracksToPlaylist`),
      { timeout: 3_000 },
    );
    await page.locator('app-add-to-playlist .picker-row', { hasText: 'Road Trip' }).click();
    const got = await req;
    const decoded = fromBinary(
      AddTracksToPlaylistRequestSchema,
      unframeGrpcWebRequest(got.postDataBuffer()),
    );
    // Picker targeted Road Trip (id=1); track_ids carries the row the user picked.
    expect(Number(decoded.id)).toBe(1);
    expect(decoded.trackIds.length).toBeGreaterThan(0);
    // Picker auto-closes on pick.
    await expect(page.locator('app-add-to-playlist .picker')).toHaveCount(0);
  });
});

test.describe('add-to-playlist — create new', () => {
  test('Create-new prompts for a name then calls CreatePlaylist + AddTracksToPlaylist', async ({ page }) => {
    await setup(page);
    await openTrackPicker(page);
    page.on('dialog', d => d.accept('Late-Night Jazz'));

    const created = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith(`${PS}/CreatePlaylist`),
      { timeout: 3_000 },
    );
    const tracksAdded = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith(`${PS}/AddTracksToPlaylist`),
      { timeout: 3_000 },
    );
    await page.locator('app-add-to-playlist .create-row').click();
    const createReq = await created;
    const decoded = fromBinary(
      CreatePlaylistRequestSchema,
      unframeGrpcWebRequest(createReq.postDataBuffer()),
    );
    expect(decoded.name).toBe('Late-Night Jazz');
    // hasDescription() distinguishes "absent" from "empty"; the SPA
    // sends description undefined for a blank input, which the proto
    // serialises as field-absent.
    expect(decoded.description).toBeUndefined();
    const trackReq = await tracksAdded;
    const trackDecoded = fromBinary(
      AddTracksToPlaylistRequestSchema,
      unframeGrpcWebRequest(trackReq.postDataBuffer()),
    );
    expect(Number(trackDecoded.id)).toBe(99);
  });

  test('Create-new with a blank name leaves the picker open and fires nothing', async ({ page }) => {
    await setup(page);
    await openTrackPicker(page);
    let fired = false;
    page.on('request', r => {
      if (r.method() === 'POST' && r.url().endsWith(`${PS}/CreatePlaylist`)) fired = true;
    });
    page.on('dialog', d => d.accept('   '));
    await page.locator('app-add-to-playlist .create-row').click();
    // Give Angular a frame to settle so any spurious POST would have fired.
    await page.waitForTimeout(200);
    expect(fired).toBe(false);
    await expect(page.locator('app-add-to-playlist .picker')).toBeVisible();
  });

  test('Create-new with prompt cancelled is a no-op', async ({ page }) => {
    await setup(page);
    await openTrackPicker(page);
    let fired = false;
    page.on('request', r => {
      if (r.method() === 'POST' && r.url().endsWith(`${PS}/CreatePlaylist`)) fired = true;
    });
    page.on('dialog', d => d.dismiss());
    await page.locator('app-add-to-playlist .create-row').click();
    await page.waitForTimeout(200);
    expect(fired).toBe(false);
    await expect(page.locator('app-add-to-playlist .picker')).toBeVisible();
  });
});

test.describe('add-to-playlist — empty + error states', () => {
  test('empty owned-playlist list renders the helpful empty hint', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route(`**${PS}/ListPlaylists`, r =>
      fulfillProto(r, ListPlaylistsResponseSchema, create(ListPlaylistsResponseSchema)));
    await page.goto('/title/301');
    await page.waitForSelector('app-title-detail');
    await openTrackPicker(page);
    await expect(page.locator('app-add-to-playlist .picker-empty'))
      .toContainText("don't own any playlists");
    // Create-new affordance still renders for the user to bootstrap one.
    await expect(page.locator('app-add-to-playlist .create-row')).toBeVisible();
  });

  test('failed playlist list load shows the error message', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route(`**${PS}/ListPlaylists`, r => r.fulfill({ status: 500 }));
    await page.goto('/title/301');
    await page.waitForSelector('app-title-detail');
    await openTrackPicker(page);
    await expect(page.locator('app-add-to-playlist .picker-error'))
      .toContainText('Failed to load');
  });
});
