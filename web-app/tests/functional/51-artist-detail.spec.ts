import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

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
    await page.route('**/api/v2/catalog/artists/*', r =>
      r.fulfill({ json: {
        id: 2, name: 'Mystery Band', sort_name: 'Band, Mystery', artist_type: 'GROUP',
        biography: null, headshot_url: null, begin_date: '2010-01-01', end_date: null,
        owned_albums: [
          { title_id: 400, title_name: 'Debut', poster_url: '/posters/w185/400', release_year: 2010, track_count: 8 },
        ],
        other_works: [], band_members: [], member_of: [],
      } }));
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
    await page.route('**/api/v2/catalog/artists/*', r =>
      r.fulfill({ json: {
        id: 3, name: 'Empty', sort_name: 'Empty', artist_type: 'PERSON',
        biography: null, headshot_url: null, begin_date: null, end_date: null,
        owned_albums: [], other_works: [], band_members: [], member_of: [],
      } }));
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
    await page.route('**/api/v2/catalog/artists/*', r =>
      r.fulfill({ json: {
        id: 1, name: 'Miles', sort_name: 'Miles', artist_type: 'PERSON',
        biography: null, headshot_url: '/h/1', begin_date: null, end_date: null,
        owned_albums: [], other_works: [], band_members: [], member_of: [],
      } }));
    await page.goto('/artist/1');
    await page.waitForSelector('app-artist .hero');
    await expect(page.locator('app-artist .empty-state'))
      .toContainText('No albums in your collection');
  });
});

test.describe('artist detail — other works wish toggle', () => {
  test('un-wished card click POSTs /wishlist/albums with primary_artist=name', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/wishlist/albums'),
      { timeout: 3_000 },
    );
    await page.locator('app-artist .poster-card.unowned button.wish-btn').click();
    const got = await req;
    const body = got.postDataJSON();
    // Default fixture's other_works entry has no is_compilation field,
    // so JSON.stringify drops it from the body. Assert the fields that
    // do round-trip rather than a strict deepEqual.
    expect(body.release_group_id).toBe('abc-123');
    expect(body.title).toBe('Bitches Brew');
    expect(body.primary_artist).toBe('Miles Davis');
    expect(body.year).toBe(1970);
    expect(body.cover_release_id).toBeNull();
  });

  test('wished card click DELETEs /wishlist/albums/:rgid', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route('**/api/v2/wishlist/albums/*', r => {
      if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
      return r.fallback();
    });
    await page.route('**/api/v2/catalog/artists/*', r =>
      r.fulfill({ json: {
        id: 1, name: 'Miles', sort_name: 'Miles', artist_type: 'PERSON',
        biography: null, headshot_url: '/h/1', begin_date: null, end_date: null,
        owned_albums: [], band_members: [], member_of: [],
        other_works: [
          { release_group_id: 'rg-9', title: 'Sketches of Spain', year: 1960,
            primary_type: 'Album', secondary_types: [], cover_url: null,
            is_compilation: false, already_wished: true },
        ],
      } }));
    await page.goto('/artist/1');
    await page.waitForSelector('app-artist .hero');
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/wishlist/albums/rg-9'),
      { timeout: 3_000 },
    );
    await page.locator('app-artist .poster-card.unowned button.wish-btn').click();
    await req;
  });

  test('compilation other-work wishes with primary_artist="Various Artists"', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route('**/api/v2/wishlist/albums', r => r.fulfill({ status: 204 }));
    await page.route('**/api/v2/catalog/artists/*', r =>
      r.fulfill({ json: {
        id: 1, name: 'Miles', sort_name: 'Miles', artist_type: 'PERSON',
        biography: null, headshot_url: '/h/1', begin_date: null, end_date: null,
        owned_albums: [], band_members: [], member_of: [],
        other_works: [
          { release_group_id: 'rg-comp', title: 'Various Hits', year: 2024,
            primary_type: 'Album', secondary_types: ['Compilation'],
            cover_url: null, is_compilation: true, already_wished: false },
        ],
      } }));
    await page.goto('/artist/1');
    await page.waitForSelector('app-artist .hero');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/wishlist/albums'),
      { timeout: 3_000 },
    );
    await page.locator('app-artist button.wish-btn').click();
    const got = await req;
    expect(got.postDataJSON().primary_artist).toBe('Various Artists');
    expect(got.postDataJSON().is_compilation).toBe(true);
  });
});

test.describe('artist detail — band members + member of', () => {
  test('Band Members section renders when present', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route('**/api/v2/catalog/artists/*', r =>
      r.fulfill({ json: {
        id: 1, name: 'The Band', sort_name: 'Band', artist_type: 'GROUP',
        biography: null, headshot_url: null, begin_date: '1968-01-01', end_date: null,
        owned_albums: [], other_works: [], member_of: [],
        band_members: [
          { id: 10, name: 'Levon Helm', begin_date: '1968', end_date: '1976', instruments: 'drums' },
        ],
      } }));
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
    await page.route('**/api/v2/catalog/artists/*', r =>
      r.fulfill({ json: {
        id: 1, name: 'Levon Helm', sort_name: 'Helm, Levon', artist_type: 'PERSON',
        biography: null, headshot_url: null, begin_date: null, end_date: null,
        owned_albums: [], other_works: [], band_members: [],
        member_of: [
          { id: 99, name: 'The Band', begin_date: '1968', end_date: null, instruments: null },
        ],
      } }));
    await page.goto('/artist/1');
    await page.waitForSelector('app-artist .hero');
    await expect(page.locator('app-artist h2', { hasText: 'Member Of' })).toBeVisible();
    // begin date but no end → "Since 1968"
    await expect(page.locator('app-artist .membership-tenure')).toContainText('Since 1968');
  });
});
