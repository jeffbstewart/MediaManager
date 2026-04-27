import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto, unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { clone, create, fromBinary } from '@bufbuild/protobuf';
import {
  AlbumPersonnelEntrySchema,
  ArtistType,
  ColorSchema,
  MediaFormat,
  PersonnelRole,
  PlaybackProgressSchema,
  Quality,
  SetTrackMusicTagsRequestSchema,
  SetTrackTagsRequestSchema,
  TagListResponseSchema,
  TagSchema,
  TitleDetailSchema,
  TrackSchema,
  TranscodeSchema,
  type TitleDetail,
  type Transcode,
} from '../../src/app/proto-gen/common_pb';
import { PlaybackOffsetSchema } from '../../src/app/proto-gen/time_pb';
import { titleMovie100 } from '../fixtures-typed/title-100-movie.fixture';
import { titleTv200 } from '../fixtures-typed/title-200-tv.fixture';
import { titleAlbum301 } from '../fixtures-typed/title-301-album.fixture';

/** Movie 100 detail post-migration runs through gRPC-Web; per-test
 * variants override that endpoint with a typed-fixture clone instead
 * of the legacy REST route. `clone` deep-copies the message so mutations
 * don't bleed into the canonical fixture. */
async function overrideMovie100(page: Page, mutate: (d: TitleDetail) => void): Promise<void> {
  const variant = clone(TitleDetailSchema, titleMovie100);
  mutate(variant);
  await page.route('**/mediamanager.CatalogService/GetTitleDetail',
    r => fulfillProto(r, TitleDetailSchema, variant));
}

/** Same shape as overrideMovie100 but for the TV-200 fixture. */
async function overrideTv200(page: Page, mutate: (d: TitleDetail) => void): Promise<void> {
  const variant = clone(TitleDetailSchema, titleTv200);
  mutate(variant);
  await page.route('**/mediamanager.CatalogService/GetTitleDetail',
    r => fulfillProto(r, TitleDetailSchema, variant));
}

/** Same shape as overrideMovie100 but for the album-301 fixture. */
async function overrideAlbum301(page: Page, mutate: (d: TitleDetail) => void): Promise<void> {
  const variant = clone(TitleDetailSchema, titleAlbum301);
  mutate(variant);
  await page.route('**/mediamanager.CatalogService/GetTitleDetail',
    r => fulfillProto(r, TitleDetailSchema, variant));
}

// title-detail — album branch + admin track-row actions. Targets
// title-detail.ts coverage gaps: musicMetaTitle, editTrackMusicTags,
// removeTrackTag, openTrackTagPicker, rescanAlbum, startRadio,
// personnelByRole, albumDiscs grouping, hide-confirm cancel/unhide,
// resumeTranscode getter, error state.

const ALBUM_ID = 301;
const MOVIE_ID = 100;

async function setupAlbumViewer(page: Page) {
  await mockBackend(page, { features: 'viewer' });
  await loginAs(page);
  await stubImages(page);
  await page.goto(`/title/${ALBUM_ID}`);
  await page.waitForSelector('app-title-detail .detail-container');
}

async function setupAlbumAdmin(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await stubImages(page);
  // ListTagsForTrack / SetTrackTags / SetTrackMusicTags are no-op'd
  // by mock-backend's default gRPC dispatch. Tests that need to
  // capture the request register an override before this helper.
  await page.goto(`/title/${ALBUM_ID}`);
  await page.waitForSelector('app-title-detail .detail-container');
}

test.describe('title detail — album hero + tracks', () => {
  test('renders square hero poster + album-action buttons', async ({ page }) => {
    await setupAlbumViewer(page);
    await expect(page.locator('img.hero-poster')).toHaveClass(/hero-poster-square/);
    await expect(page.locator('.album-actions button.resume-btn').first())
      .toContainText('Play Album');
    await expect(page.locator('.album-actions .album-more-btn')).toBeVisible();
  });

  test('artist link points at /artist/:id', async ({ page }) => {
    await setupAlbumViewer(page);
    await expect(page.locator('.byline a').first())
      .toHaveAttribute('href', /\/artist\/1$/);
  });

  test('track rows render with name + duration formatted as m:ss', async ({ page }) => {
    await setupAlbumViewer(page);
    const rows = page.locator('.track-row');
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0)).toContainText('So What');
    // 565s → 9:25
    await expect(rows.nth(0).locator('.track-duration')).toContainText('9:25');
    // 589s → 9:49
    await expect(rows.nth(1).locator('.track-duration')).toContainText('9:49');
  });

  test('track Play button starts the album queue', async ({ page }) => {
    await setupAlbumViewer(page);
    await page.route('**/audio/*', r => r.fulfill({ status: 204 }));
    await page.locator('.track-row .track-play-btn').nth(2).click(); // 2nd track's play
    await expect(page.locator('app-audio-player .audio-player'))
      .toHaveClass(/visible/, { timeout: 2_000 });
  });
});

test.describe('title detail — album multi-disc + personnel', () => {
  test('multi-disc album groups tracks under "Disc N" headers', async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
    await overrideAlbum301(page, d => {
      d.album!.tracks = [
        create(TrackSchema, {
          id: 1n, titleId: 301n, discNumber: 1, trackNumber: 1, name: 'D1T1',
          duration: { seconds: 60 }, playable: true,
        }),
        create(TrackSchema, {
          id: 2n, titleId: 301n, discNumber: 2, trackNumber: 1, name: 'D2T1',
          duration: { seconds: 90 }, playable: true,
        }),
      ];
      d.album!.trackCount = 2;
      d.album!.totalDuration = create(PlaybackOffsetSchema, { seconds: 150 });
    });
    await page.goto(`/title/${ALBUM_ID}`);
    await page.waitForSelector('app-title-detail .detail-container');
    await expect(page.locator('.disc-header')).toHaveCount(2);
    await expect(page.locator('.disc-header').first()).toContainText('Disc 1');
  });

  test('personnel section renders, collapsed by default + expand toggle works', async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
    await overrideAlbum301(page, d => {
      d.album!.tracks = [
        create(TrackSchema, {
          id: 1n, titleId: 301n, discNumber: 1, trackNumber: 1, name: 'T1',
          duration: { seconds: 60 }, playable: true,
        }),
      ];
      d.album!.trackCount = 1;
      d.album!.totalDuration = create(PlaybackOffsetSchema, { seconds: 60 });
      d.album!.personnel = [
        create(AlbumPersonnelEntrySchema, {
          artistId: 10n, artistName: 'Player A', role: PersonnelRole.PERFORMER,
          instrument: 'piano', trackId: 1n, trackName: 'T1',
        }),
        create(AlbumPersonnelEntrySchema, {
          artistId: 11n, artistName: 'Producer Q', role: PersonnelRole.PRODUCER,
        }),
        create(AlbumPersonnelEntrySchema, {
          artistId: 12n, artistName: 'Eng E', role: PersonnelRole.ENGINEER,
        }),
      ];
    });
    await page.goto(`/title/${ALBUM_ID}`);
    await page.waitForSelector('app-title-detail .detail-container');
    // Collapsed by default — hint visible, groups absent.
    await expect(page.locator('.personnel-hint')).toContainText('3 credits');
    await expect(page.locator('.personnel-groups')).toHaveCount(0);
    await page.locator('.personnel-toggle').click();
    await expect(page.locator('.personnel-groups')).toBeVisible();
    // Three role buckets (Performers, Producers, Engineers) in display order.
    await expect(page.locator('.personnel-role')).toHaveCount(3);
    await expect(page.locator('.personnel-role').first()).toContainText('Performers');
    await expect(page.locator('.personnel-instrument')).toContainText('piano');
    await expect(page.locator('.personnel-track')).toContainText('on T1');
  });
});

test.describe('title detail — album admin: track tag picker + remove', () => {
  test('"Tag this track" menu item opens app-tag-picker', async ({ page }) => {
    await setupAlbumAdmin(page);
    await page.locator('.track-row button[aria-label="More track actions"]').first().click();
    // ListTagsForTrack runs as a POST gRPC call. Default mock-backend
    // dispatch returns an empty tag list — that's enough for the
    // picker to render.
    const fetched = page.waitForRequest(r =>
      r.method() === 'POST'
      && r.url().endsWith('/mediamanager.CatalogService/ListTagsForTrack'));
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Tag this track' }).click();
    await fetched;
    await expect(page.locator('app-tag-picker .picker')).toBeVisible({ timeout: 2_000 });
  });

  test('removeTrackTag posts the filtered tag set', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    // Override album fixture to give track 4001 an existing tag.
    await overrideAlbum301(page, d => {
      d.album!.tracks = [
        create(TrackSchema, {
          id: 4001n, titleId: 301n, discNumber: 1, trackNumber: 1, name: 'So What',
          duration: { seconds: 565 }, playable: true,
          tags: [
            create(TagSchema, {
              id: 7n, name: 'Mellow', color: create(ColorSchema, { hex: '#333' }),
            }),
          ],
        }),
      ];
      d.album!.trackCount = 1;
      d.album!.totalDuration = create(PlaybackOffsetSchema, { seconds: 565 });
    });
    await page.goto(`/title/${ALBUM_ID}`);
    await page.waitForSelector('app-title-detail .detail-container');
    // The remove button calls SetTrackTags via gRPC. Capture the
    // request and decode the protobuf body to assert the filtered set.
    const posted = page.waitForRequest(r =>
      r.method() === 'POST'
      && r.url().endsWith('/mediamanager.CatalogService/SetTrackTags'),
      { timeout: 3_000 },
    );
    await page.locator('.track-tag-remove-btn').first().click();
    const got = await posted;
    const decoded = fromBinary(
      SetTrackTagsRequestSchema,
      unframeGrpcWebRequest(got.postDataBuffer()),
    );
    expect(decoded.trackId).toBe(4001n);
    expect(decoded.tagIds).toEqual([]);
  });
});

test.describe('title detail — album admin: edit BPM & time signature', () => {
  async function openEditMenu(page: Page) {
    await page.locator('.track-row button[aria-label="More track actions"]').first().click();
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Edit BPM' }).click();
  }

  // SetTrackMusicTags is a gRPC RPC; its endpoint URL ends with the
  // RPC name so the tests below match on the gRPC path. The request
  // body is gRPC-Web framed binary, decoded via fromBinary().
  const SET_MUSIC_TAGS_URL = '/mediamanager.CatalogService/SetTrackMusicTags';

  test('happy path: prompts for BPM + time-sig, posts both', async ({ page }) => {
    await setupAlbumAdmin(page);
    const dialogs: string[] = [];
    page.on('dialog', d => {
      dialogs.push(d.message());
      d.accept(dialogs.length === 1 ? '128' : '4/4');
    });
    const posted = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith(SET_MUSIC_TAGS_URL),
      { timeout: 3_000 },
    );
    await openEditMenu(page);
    const got = await posted;
    const decoded = fromBinary(
      SetTrackMusicTagsRequestSchema,
      unframeGrpcWebRequest(got.postDataBuffer()),
    );
    expect(decoded.trackId).toBe(4001n);
    expect(decoded.bpm).toBe(128);
    expect(decoded.timeSignature).toBe('4/4');
    expect(dialogs[0]).toMatch(/BPM/i);
    expect(dialogs[1]).toMatch(/Time signature/i);
  });

  test('cancel BPM prompt aborts (no POST)', async ({ page }) => {
    await setupAlbumAdmin(page);
    let fired = false;
    page.on('request', r => {
      if (r.method() === 'POST' && r.url().endsWith(SET_MUSIC_TAGS_URL)) fired = true;
    });
    page.on('dialog', d => d.dismiss());
    await openEditMenu(page);
    await page.waitForTimeout(200);
    expect(fired).toBe(false);
  });

  test('invalid BPM (>999) shows alert, no POST', async ({ page }) => {
    await setupAlbumAdmin(page);
    let fired = false;
    page.on('request', r => {
      if (r.method() === 'POST' && r.url().endsWith(SET_MUSIC_TAGS_URL)) fired = true;
    });
    let alerted = false;
    page.on('dialog', d => {
      if (d.type() === 'alert') { alerted = true; d.accept(); return; }
      d.accept('5000');
    });
    await openEditMenu(page);
    await page.waitForTimeout(300);
    expect(fired).toBe(false);
    expect(alerted).toBe(true);
  });

  test('invalid time signature shows alert, no POST', async ({ page }) => {
    await setupAlbumAdmin(page);
    let fired = false;
    page.on('request', r => {
      if (r.method() === 'POST' && r.url().endsWith(SET_MUSIC_TAGS_URL)) fired = true;
    });
    let alerted = false;
    let promptCount = 0;
    page.on('dialog', d => {
      if (d.type() === 'alert') { alerted = true; d.accept(); return; }
      promptCount++;
      // 1st prompt = BPM (accept "120"), 2nd = time-sig (accept garbage).
      d.accept(promptCount === 1 ? '120' : 'garbage');
    });
    await openEditMenu(page);
    await page.waitForTimeout(300);
    expect(fired).toBe(false);
    expect(alerted).toBe(true);
  });

  test('blank BPM + blank time-sig sets clear flags', async ({ page }) => {
    await setupAlbumAdmin(page);
    page.on('dialog', d => d.accept(''));
    const posted = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith(SET_MUSIC_TAGS_URL),
      { timeout: 3_000 },
    );
    await openEditMenu(page);
    const got = await posted;
    const decoded = fromBinary(
      SetTrackMusicTagsRequestSchema,
      unframeGrpcWebRequest(got.postDataBuffer()),
    );
    // Tri-state contract: clearBpm/clearTimeSignature flag the
    // intent to delete. The bpm/time_signature fields stay absent
    // when clearing.
    expect(decoded.trackId).toBe(4001n);
    expect(decoded.clearBpm).toBe(true);
    expect(decoded.clearTimeSignature).toBe(true);
  });
});

test.describe('title detail — album admin: rescan + radio', () => {
  test('Rescan files posts /admin/albums/:id/rescan and alerts the summary', async ({ page }) => {
    await setupAlbumAdmin(page);
    await page.route('**/api/v2/admin/albums/301/rescan', r =>
      r.fulfill({ json: {
        linked: 2, skipped_already_linked: 0, no_match: 1,
        candidates_considered: 5, files_walked: 8,
        files_already_linked_elsewhere: 0, files_wrong_album_tag: 1,
        files_path_rejected: 2, files_accepted_by_artist_position: 0,
        rejected_album_tag_samples: ['Other Album'],
        roots_walked: ['/music'], music_root_configured: '/music',
        unlinked_after_rescan: [], message: null,
      } }));
    let alertText = '';
    page.on('dialog', d => { alertText = d.message(); d.accept(); });
    const posted = page.waitForRequest(r =>
      r.method() === 'POST' && /\/api\/v2\/admin\/albums\/301\/rescan$/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('.album-more-btn').click();
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Rescan files' }).click();
    await posted;
    // Wait for the alert handler to capture text.
    await page.waitForTimeout(200);
    expect(alertText).toContain('linked 2');
    expect(alertText).toContain('Other Album');
  });

  test('Rescan failure alerts the error message', async ({ page }) => {
    await setupAlbumAdmin(page);
    await page.route('**/api/v2/admin/albums/301/rescan', r => r.fulfill({ status: 500 }));
    let alertText = '';
    page.on('dialog', d => { alertText = d.message(); d.accept(); });
    await page.locator('.album-more-btn').click();
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Rescan files' }).click();
    await page.waitForTimeout(400);
    expect(alertText).toMatch(/Rescan failed/i);
  });

  test('Start Radio posts /api/v2/radio/start with album seed', async ({ page }) => {
    await setupAlbumAdmin(page);
    await page.route('**/api/v2/radio/start', r => r.fulfill({ json: {
      radio_seed_id: 'rs-1',
      seed: { type: 'ALBUM', id: 301, label: 'Kind of Blue' },
      tracks: [],
    } }));
    const posted = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/radio/start'),
      { timeout: 3_000 },
    );
    await page.locator('.album-more-btn').click();
    await page.locator('.mat-mdc-menu-panel button', { hasText: 'Start Radio' }).click();
    const got = await posted;
    expect(got.postDataJSON()).toEqual({ seed_type: 'album', seed_id: 301 });
  });
});

test.describe('title detail — hide flow + resume + error states', () => {
  test('hide-confirm cancelled does not POST', async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
    let fired = false;
    page.on('request', r => {
      if (r.method() === 'POST' && r.url().endsWith(`/titles/${MOVIE_ID}/hide`)) fired = true;
    });
    page.on('dialog', d => d.dismiss());
    await page.goto(`/title/${MOVIE_ID}`);
    await page.waitForSelector('app-title-detail .detail-container');
    await page.locator('button.hide-btn').click();
    await page.waitForTimeout(200);
    expect(fired).toBe(false);
  });

  test('unhide flow skips confirm and posts directly', async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
    // Override movie fixture with isHidden=true. Same typed-fixture
    // shape as the base — just one field flipped.
    await overrideMovie100(page, d => { d.isHidden = true; });
    await page.route('**/api/v2/catalog/titles/100/hide', r =>
      r.fulfill({ json: { is_hidden: false } }));
    let dialogFired = false;
    page.on('dialog', d => { dialogFired = true; d.accept(); });
    await page.goto(`/title/${MOVIE_ID}`);
    await page.waitForSelector('app-title-detail .detail-container');
    const posted = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/hide'),
      { timeout: 3_000 },
    );
    await page.locator('button.hide-btn').click();
    await posted;
    // No confirm prompt for the unhide branch.
    expect(dialogFired).toBe(false);
  });

  test('resumeTranscode renders the big "Resume from m:ss" button', async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
    // Add a saved-progress transcode so the resumeTranscode getter
    // surfaces the big "Resume from m:ss" CTA. Position lives on
    // PlaybackProgress (not Transcode) in the proto schema.
    await overrideMovie100(page, d => {
      const tc: Transcode = create(TranscodeSchema, {
        id: 1001n,
        mediaFormat: MediaFormat.BLURAY,
        quality: Quality.HD,
        playable: true,
      });
      d.transcodes = [tc];
      d.playbackProgress = create(PlaybackProgressSchema, {
        transcodeId: 1001n,
        position: create(PlaybackOffsetSchema, { seconds: 754 }),
        duration: create(PlaybackOffsetSchema, { seconds: 7800 }),
      });
    });
    await page.goto(`/title/${MOVIE_ID}`);
    await page.waitForSelector('app-title-detail .detail-container');
    const resumeBtn = page.locator('a.resume-btn').first();
    await expect(resumeBtn).toBeVisible();
    // 754s → 12:34
    await expect(resumeBtn).toContainText('Resume from 12:34');
    await expect(resumeBtn).toHaveAttribute('href', /\/play\/1001/);
  });

  test('resumeLabel for an episode renders SxxExx prefix', async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
    await overrideTv200(page, d => {
      // Replace transcodes + playbackProgress with the under-test setup.
      // The SxxExx prefix comes from Transcode's season/episode fields,
      // and "Resume from 2:10" comes from PlaybackProgress.position=130s.
      d.transcodes = [create(TranscodeSchema, {
        id: 5001n,
        mediaFormat: MediaFormat.BLURAY,
        quality: Quality.HD,
        playable: true,
        seasonNumber: 2,
        episodeNumber: 3,
        episodeName: 'Test EP',
      })];
      d.playbackProgress = create(PlaybackProgressSchema, {
        transcodeId: 5001n,
        position: create(PlaybackOffsetSchema, { seconds: 130 }),
        duration: create(PlaybackOffsetSchema, { seconds: 3000 }),
      });
    });
    await page.goto('/title/200');
    await page.waitForSelector('app-title-detail .detail-container');
    const resume = page.locator('a.resume-btn').first();
    await expect(resume).toContainText('S02E03');
    await expect(resume).toContainText('Test EP');
    await expect(resume).toContainText('Resume from 2:10');
  });

  test('error: getTitleDetail 500 renders the error banner', async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
    await page.route('**/mediamanager.CatalogService/GetTitleDetail',
      r => r.fulfill({ status: 500 }));
    await page.goto(`/title/${MOVIE_ID}`);
    await page.waitForSelector('app-title-detail');
    await expect(page.locator('app-title-detail')).toContainText('Failed to load title');
  });
});
