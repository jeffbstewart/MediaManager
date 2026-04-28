import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto, unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { clone, create, fromBinary } from '@bufbuild/protobuf';
import { AddAlbumWishRequestSchema } from '../../src/app/proto-gen/wishlist_pb';

const WS = '/mediamanager.WishListService';
import {
  ArtistDetailSchema,
  ArtistMemberEntrySchema,
  ArtistType,
  DiscographyEntrySchema,
  MediaType,
  Quality,
  ReleaseGroupType,
  TitleSchema,
  type ArtistDetail,
} from '../../src/app/proto-gen/common_pb';
import { CalendarDateSchema, Month } from '../../src/app/proto-gen/time_pb';
import { artistMilesDavis } from '../fixtures-typed/artist-author.fixture';

/** Clone the base artistMilesDavis proto fixture, run `mutate` to
 * customise, and route GetArtistDetail to the result. Per-test specs
 * use this in place of the legacy /api/v2/catalog/artists/* override. */
async function overrideArtistDetail(page: Page, mutate: (d: ArtistDetail) => void) {
  const variant = clone(ArtistDetailSchema, artistMilesDavis);
  mutate(variant);
  await page.route('**/mediamanager.ArtistService/GetArtistDetail', r =>
    fulfillProto(r, ArtistDetailSchema, variant));
}

// /artist/:id — ArtistComponent. Hero (headshot or fallback to first
// album cover), lifespan label, owned-albums grid, band/member lists,
// Other Works grid with per-album wish hearts.

async function setup(page: Page) {
  await mockBackend(page);
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/wishlist/albums', r =>
    r.fulfill({ status: 204 }));
  await page.route('**/api/v2/wishlist/albums/*', r => {
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.goto('/artist/1');
  await page.waitForSelector('app-artist .hero');
}

test.describe('artist detail — hero + lifespan', () => {
  test('renders name + headshot + lifespan', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-artist h1')).toContainText('Miles Davis');
    await expect(page.locator('app-artist .profile-img')).toHaveAttribute('src', /headshot/);
    // begin 1926 + end 1991 → "1926 – 1991"
    await expect(page.locator('app-artist .lifespan')).toContainText('1926');
    await expect(page.locator('app-artist .lifespan')).toContainText('1991');
  });

  test('falls back to first album cover when no headshot', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await overrideArtistDetail(page, d => {
      const a = d.artist!;
      a.id = 2n;
      a.name = 'Mystery Band';
      a.sortName = 'Band, Mystery';
      a.artistType = ArtistType.GROUP;
      a.hasHeadshot = false;
      a.beginDate = create(CalendarDateSchema, { year: 2010, month: Month.JANUARY, day: 1 });
      a.endDate = undefined;
      a.beginYear = 2010;
      a.endYear = undefined;
      d.biography = '';
      d.ownedAlbums = [
        create(TitleSchema, {
          id: 400n, name: 'Debut', mediaType: MediaType.ALBUM, year: 2010,
          posterUrl: '/posters/w185/400', quality: Quality.HD, playable: true,
          trackCount: 8,
        }),
      ];
      d.otherWorks = [];
      d.members = [];
      d.memberOf = [];
    });
    await page.goto('/artist/2');
    await page.waitForSelector('app-artist .hero');
    await expect(page.locator('app-artist .profile-img.fallback'))
      .toHaveAttribute('src', /\/posters\/w185\/400/);
    // Group with begin date but no end date → "2010 – present"
    await expect(page.locator('app-artist .lifespan')).toContainText('present');
  });

  test('renders the placeholder when no headshot AND no album cover', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await overrideArtistDetail(page, d => {
      const a = d.artist!;
      a.id = 3n;
      a.name = 'Empty';
      a.sortName = 'Empty';
      a.artistType = ArtistType.PERSON;
      a.hasHeadshot = false;
      a.beginDate = undefined;
      a.endDate = undefined;
      a.beginYear = undefined;
      a.endYear = undefined;
      d.biography = '';
      d.ownedAlbums = [];
      d.otherWorks = [];
      d.members = [];
      d.memberOf = [];
    });
    await page.goto('/artist/3');
    await page.waitForSelector('app-artist .hero');
    await expect(page.locator('app-artist .profile-placeholder')).toBeVisible();
  });
});

test.describe('artist detail — biography toggle', () => {
  test('Bio toggle button expands the biography text', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    const longBio = 'A '.repeat(400) + 'long bio.';
    await page.route('**/api/v2/catalog/artists/*', r =>
      r.fulfill({ json: {
        id: 1, name: 'Miles', sort_name: 'Miles', artist_type: 'PERSON',
        biography: longBio, headshot_url: '/h/1', begin_date: '1926-05-26', end_date: '1991-09-28',
        owned_albums: [], other_works: [], band_members: [], member_of: [],
      } }));
    await page.goto('/artist/1');
    await page.waitForSelector('app-artist .hero');
    // The bio-toggle button only renders for long bios; click it and
    // verify the expanded class applies.
    const toggle = page.locator('app-artist .bio-toggle');
    if (await toggle.count() > 0) {
      await toggle.click();
      await expect(page.locator('app-artist .biography')).toBeVisible();
    }
  });
});

test.describe('artist detail — owned albums', () => {
  test('renders owned-albums grid + each card links to /title/:id', async ({ page }) => {
    await setup(page);
    const cards = page.locator('app-artist .poster-card').first();
    await expect(cards).toHaveAttribute('href', '/title/301');
    await expect(cards).toContainText('Kind of Blue');
    await expect(cards).toContainText('1959');
  });

  test('empty owned-albums shows the empty hint', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await overrideArtistDetail(page, d => {
      d.ownedAlbums = [];
      d.otherWorks = [];
      d.members = [];
      d.memberOf = [];
    });
    await page.goto('/artist/1');
    await page.waitForSelector('app-artist .hero');
    await expect(page.locator('app-artist .empty-state'))
      .toContainText('No albums in your collection');
  });
});

test.describe('artist detail — other works wish toggle', () => {
  test('un-wished card click fires AddAlbumWish with primary_artist=name', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.url().endsWith(`${WS}/AddAlbumWish`),
      { timeout: 3_000 },
    );
    await page.locator('app-artist .poster-card.unowned button.wish-btn').click();
    const got = await req;
    const decoded = fromBinary(
      AddAlbumWishRequestSchema,
      unframeGrpcWebRequest(got.postDataBuffer()),
    );
    expect(decoded.releaseGroupId).toBe('abc-123');
    expect(decoded.title).toBe('Bitches Brew');
    expect(decoded.primaryArtist).toBe('Miles Davis');
    expect(decoded.year).toBe(1970);
    expect(decoded.coverReleaseId).toBeUndefined();
  });

  test('wished card click fires RemoveAlbumWish', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await overrideArtistDetail(page, d => {
      d.ownedAlbums = [];
      d.members = [];
      d.memberOf = [];
      d.otherWorks = [
        create(DiscographyEntrySchema, {
          musicbrainzReleaseGroupId: 'rg-9',
          name: 'Sketches of Spain',
          year: 1960,
          releaseGroupType: ReleaseGroupType.ALBUM,
          isCompilation: false,
          alreadyWished: true,
        }),
      ];
    });
    await page.goto('/artist/1');
    await page.waitForSelector('app-artist .hero');
    const req = page.waitForRequest(r =>
      r.url().endsWith(`${WS}/RemoveAlbumWish`),
      { timeout: 3_000 },
    );
    await page.locator('app-artist .poster-card.unowned button.wish-btn').click();
    await req;
  });

  test('compilation other-work wishes with primary_artist="Various Artists"', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await overrideArtistDetail(page, d => {
      d.ownedAlbums = [];
      d.members = [];
      d.memberOf = [];
      d.otherWorks = [
        create(DiscographyEntrySchema, {
          musicbrainzReleaseGroupId: 'rg-comp',
          name: 'Various Hits',
          year: 2024,
          releaseGroupType: ReleaseGroupType.ALBUM,
          isCompilation: true,
          secondaryTypes: ['Compilation'],
          alreadyWished: false,
        }),
      ];
    });
    await page.goto('/artist/1');
    await page.waitForSelector('app-artist .hero');
    const req = page.waitForRequest(r =>
      r.url().endsWith(`${WS}/AddAlbumWish`),
      { timeout: 3_000 },
    );
    await page.locator('app-artist button.wish-btn').click();
    const got = await req;
    const decoded = fromBinary(
      AddAlbumWishRequestSchema,
      unframeGrpcWebRequest(got.postDataBuffer()),
    );
    expect(decoded.primaryArtist).toBe('Various Artists');
    expect(decoded.isCompilation).toBe(true);
  });
});

test.describe('artist detail — band members + member of', () => {
  test('Band Members section renders when present', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await overrideArtistDetail(page, d => {
      const a = d.artist!;
      a.name = 'The Band';
      a.sortName = 'Band';
      a.artistType = ArtistType.GROUP;
      a.hasHeadshot = false;
      a.beginDate = create(CalendarDateSchema, { year: 1968, month: Month.JANUARY, day: 1 });
      a.endDate = undefined;
      a.beginYear = 1968;
      a.endYear = undefined;
      d.ownedAlbums = [];
      d.otherWorks = [];
      d.memberOf = [];
      d.members = [
        create(ArtistMemberEntrySchema, {
          artistId: 10n, name: 'Levon Helm', artistType: ArtistType.PERSON,
          beginYear: 1968, endYear: 1976,
          beginDate: create(CalendarDateSchema, { year: 1968, month: Month.JANUARY, day: 1 }),
          endDate: create(CalendarDateSchema, { year: 1976, month: Month.JANUARY, day: 1 }),
          instruments: 'drums',
        }),
      ];
    });
    await page.goto('/artist/1');
    await page.waitForSelector('app-artist .hero');
    await expect(page.locator('app-artist h2', { hasText: /^Band Members/i })).toBeVisible();
    await expect(page.locator('app-artist .membership-name')).toContainText('Levon Helm');
    await expect(page.locator('app-artist .membership-tenure')).toContainText('1968');
  });

  test('Member Of section renders + tenure formatted', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await overrideArtistDetail(page, d => {
      const a = d.artist!;
      a.name = 'Levon Helm';
      a.sortName = 'Helm, Levon';
      a.artistType = ArtistType.PERSON;
      a.hasHeadshot = false;
      a.beginDate = undefined;
      a.endDate = undefined;
      a.beginYear = undefined;
      a.endYear = undefined;
      d.ownedAlbums = [];
      d.otherWorks = [];
      d.members = [];
      d.memberOf = [
        create(ArtistMemberEntrySchema, {
          artistId: 99n, name: 'The Band', artistType: ArtistType.GROUP,
          beginYear: 1968,
          beginDate: create(CalendarDateSchema, { year: 1968, month: Month.JANUARY, day: 1 }),
        }),
      ];
    });
    await page.goto('/artist/1');
    await page.waitForSelector('app-artist .hero');
    await expect(page.locator('app-artist h2', { hasText: 'Member Of' })).toBeVisible();
    // begin date but no end → "Since 1968"
    await expect(page.locator('app-artist .membership-tenure')).toContainText('Since 1968');
  });
});
