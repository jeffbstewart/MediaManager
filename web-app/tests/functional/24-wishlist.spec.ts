import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Wish-list view tests.
//
// /wishlist (WishListComponent) is one long page with five sections
// (Search TMDB, Media Wishes, Transcode Wishes, Book Wishes, Album
// Wishes). Each section reads from the same /api/v2/wishlist
// payload and writes through its own mutation endpoint.
//
// The default fixture (catalog/wishlist.json) is intentionally small
// — one of each row type — to keep other specs (e.g. collections)
// fast. Tests here that need richer shapes (TV row with a season,
// non-dismissible cancel-button row, transcode pending status,
// album wishes) override the GET /api/v2/wishlist response inline.

const WISHLIST_URL = '**/api/v2/wishlist';

/** Build a wishlist payload for inline route overrides. */
function wishlistPayload(over: Record<string, unknown> = {}) {
  return {
    media_wishes: [],
    transcode_wishes: [],
    book_wishes: [],
    album_wishes: [],
    has_any_media_wish: true,
    ...over,
  };
}

/** Stub the mutation endpoints with empty 200/204s so awaited promises resolve. */
async function stubWishlistMutations(page: Page) {
  await page.route('**/api/v2/wishlist/add', route =>
    route.fulfill({ json: { ok: true } }),
  );
  await page.route('**/api/v2/wishlist/*/dismiss', route =>
    route.fulfill({ status: 204 }),
  );
  // /api/v2/wishlist/transcode/:id and /api/v2/wishlist/books/:id and
  // /api/v2/wishlist/albums/:id all DELETE — separate routes so the
  // test can assert which one fired.
  await page.route('**/api/v2/wishlist/transcode/*', route =>
    route.fulfill({ status: 204 }),
  );
  await page.route('**/api/v2/wishlist/books/*', route =>
    route.fulfill({ json: { removed: true } }),
  );
  await page.route('**/api/v2/wishlist/albums/*', route =>
    route.fulfill({ json: { removed: true } }),
  );
  // The bare /api/v2/wishlist/:id DELETE collides with the GET base
  // path — only handle DELETE, fall through for GET.
  await page.route('**/api/v2/wishlist/*', route => {
    if (route.request().method() === 'DELETE') return route.fulfill({ status: 204 });
    return route.fallback();
  });
}

test.describe('wishlist — default fixture', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await stubWishlistMutations(page);
    await page.goto('/wishlist');
    await page.waitForSelector('app-wishlist .content-page');
  });

  test('renders the four section headers (Album hidden when empty)', async ({ page }) => {
    const headers = page.locator('app-wishlist h3');
    await expect(headers).toContainText(['Search TMDB', 'Media Wishes', 'Transcode Wishes', 'Book Wishes']);
    // album_wishes is [] in the default fixture → no "Album Wishes" header.
    await expect(page.locator('app-wishlist h3', { hasText: /^Album Wishes$/ })).toHaveCount(0);
  });

  test('media wish card renders poster, title, year, type "Film"', async ({ page }) => {
    const card = page.locator('app-wishlist .media-card').first();
    // Poster sources through /proxy/tmdb/w185/<file>.
    await expect(card.locator('img.media-poster'))
      .toHaveAttribute('src', /\/proxy\/tmdb\/w185\/fZPSd91yGE9fCcCe6OoQZ6ljZeB\.jpg$/);
    await expect(card.locator('.media-title')).toContainText('John Wick');
    // Movie + 2014 → "2014 · Film".
    await expect(card.locator('.media-meta')).toContainText('2014');
    await expect(card.locator('.media-meta')).toContainText('Film');
    // No season label on a movie wish.
    await expect(card.locator('.season-label')).toHaveCount(0);
    // Lifecycle pill renders the server-supplied label text.
    await expect(card.locator('.lifecycle-badge')).toContainText('Pending');
  });

  test('transcode wish renders ready status + remove button', async ({ page }) => {
    const row = page.locator('app-wishlist .transcode-list .transcode-row').first();
    await expect(row.locator('.transcode-title')).toContainText('The Matrix');
    await expect(row.locator('.transcode-status')).toContainText('Ready to watch');
    await expect(row.locator('.transcode-status')).toHaveClass(/ready/);
    await expect(row.locator('button[aria-label="Remove from wish list"]')).toBeVisible();
  });

  test('book wish renders cover, title, author', async ({ page }) => {
    // Book wishes share the .transcode-row container; the second
    // .transcode-list block on the page is the book section.
    const row = page.locator('app-wishlist .transcode-list').nth(1).locator('.transcode-row');
    await expect(row.locator('img.transcode-poster'))
      .toHaveAttribute('src', /\/posters\/w185\/302$/);
    await expect(row.locator('.transcode-title')).toContainText('Dune Messiah');
    await expect(row.locator('.book-wish-author')).toContainText('by Frank Herbert');
  });

  test('book wish remove DELETEs by ol_work_id', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/wishlist/books/OL12345W'),
      { timeout: 3_000 },
    );
    await page.locator('app-wishlist .transcode-list').nth(1)
      .locator('button[aria-label="Remove from wish list"]').click();
    await req;
  });

  test('transcode wish remove DELETEs /api/v2/wishlist/transcode/:id', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/wishlist/transcode/1'),
      { timeout: 3_000 },
    );
    await page.locator('app-wishlist .transcode-list').first()
      .locator('button[aria-label="Remove from wish list"]').click();
    await req;
  });

  test('dismissible media wish without title_id POSTs /:id/dismiss and stays on page', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/wishlist/1/dismiss'),
      { timeout: 3_000 },
    );
    // Default fixture: John Wick is dismissible=true, title_id=null
    // → renders the bottom "Dismiss" button, not the corner ×.
    await page.locator('app-wishlist .media-card').first()
      .locator('button.dismiss-btn').click();
    await req;
    await expect(page).toHaveURL(/\/wishlist$/);
  });
});

// -------- TMDB search → add flow --------

test.describe('wishlist — TMDB search', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await stubWishlistMutations(page);
    // Two results: one not yet wished, one already wished — exercise both
    // visible affordances (Add button vs ♥ Wished label).
    await page.route('**/api/v2/wishlist/search*', route =>
      route.fulfill({ json: { results: [
        {
          tmdb_id: 603, title: 'The Matrix', media_type: 'movie',
          poster_path: '/matrix.jpg', release_year: 1999, popularity: 80.5,
          already_wished: false,
        },
        {
          tmdb_id: 1399, title: 'Game of Thrones', media_type: 'TV',
          poster_path: null, release_year: 2011, popularity: 90.0,
          already_wished: true,
        },
      ] } }),
    );
    await page.goto('/wishlist');
    await page.waitForSelector('app-wishlist .content-page');
  });

  test('Search button fires GET /api/v2/wishlist/search?q=...', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.method() === 'GET' && /\/api\/v2\/wishlist\/search\?.*q=matrix/i.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-wishlist input.search-input').fill('matrix');
    await page.locator('app-wishlist button.search-btn').click();
    await req;
  });

  test('Enter key in the search input also fires search', async ({ page }) => {
    const req = page.waitForRequest(r =>
      /\/api\/v2\/wishlist\/search/.test(r.url()), { timeout: 3_000 },
    );
    const input = page.locator('app-wishlist input.search-input');
    await input.fill('matrix');
    await input.press('Enter');
    await req;
  });

  test('search results render poster, title, meta, Add or Wished label', async ({ page }) => {
    await page.locator('app-wishlist input.search-input').fill('matrix');
    await page.locator('app-wishlist button.search-btn').click();
    const cards = page.locator('app-wishlist .search-card');
    await expect(cards).toHaveCount(2);

    // Card 0: Matrix (movie, not wished) → poster + Add button.
    await expect(cards.first().locator('img.search-poster'))
      .toHaveAttribute('src', /\/proxy\/tmdb\/w185\/matrix\.jpg$/);
    await expect(cards.first().locator('.search-title')).toContainText('The Matrix');
    await expect(cards.first().locator('.search-meta')).toContainText('1999');
    await expect(cards.first().locator('.search-meta')).toContainText('Film');
    await expect(cards.first().locator('button.add-wish-btn')).toBeVisible();

    // Card 1: Game of Thrones (TV, already wished) → placeholder, no
    // Add button, ♥ Wished label instead.
    await expect(cards.nth(1).locator('.search-poster-placeholder')).toContainText('G');
    await expect(cards.nth(1).locator('.search-meta')).toContainText('TV');
    await expect(cards.nth(1).locator('button.add-wish-btn')).toHaveCount(0);
    await expect(cards.nth(1).locator('.wished-label')).toBeVisible();
  });

  test('Add fires POST /add with the right payload (no interstitial when has_any_media_wish=true)', async ({ page }) => {
    await page.locator('app-wishlist input.search-input').fill('matrix');
    await page.locator('app-wishlist button.search-btn').click();
    await page.waitForSelector('app-wishlist .search-card');

    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/wishlist/add'),
      { timeout: 3_000 },
    );
    await page.locator('app-wishlist .search-card').first()
      .locator('button.add-wish-btn').click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({
      tmdb_id: 603,
      title: 'The Matrix',
      media_type: 'movie',
      poster_path: '/matrix.jpg',
      release_year: 1999,
      popularity: 80.5,
    });

    // After the POST resolves the card flips to the "Wished" label.
    await expect(page.locator('app-wishlist .search-card').first()
      .locator('.wished-label')).toBeVisible();
  });
});

// -------- First-wish interstitial --------

test.describe('wishlist — first-wish interstitial', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await stubWishlistMutations(page);
    // has_any_media_wish=false → interstitial fires on first add.
    await page.route(/\/api\/v2\/wishlist(\?|$)/, route =>
      route.fulfill({ json: wishlistPayload({ has_any_media_wish: false }) }),
    );
    await page.route('**/api/v2/wishlist/search*', route =>
      route.fulfill({ json: { results: [{
        tmdb_id: 1, title: 'Citizen Kane', media_type: 'movie',
        poster_path: null, release_year: 1941, popularity: 50,
        already_wished: false,
      }] } }),
    );
    await page.goto('/wishlist');
    await page.waitForSelector('app-wishlist .content-page');
    await page.locator('app-wishlist input.search-input').fill('kane');
    await page.locator('app-wishlist button.search-btn').click();
    await page.waitForSelector('app-wishlist .search-card');
  });

  test('first Add shows the interstitial, Cancel suppresses the wish', async ({ page }) => {
    let addFired = false;
    page.on('request', r => {
      if (r.method() === 'POST' && r.url().endsWith('/api/v2/wishlist/add')) addFired = true;
    });
    await page.locator('app-wishlist button.add-wish-btn').click();
    await expect(page.locator('app-wishlist .modal-overlay')).toBeVisible();
    await page.locator('app-wishlist .modal-content button.cancel-btn').click();
    await expect(page.locator('app-wishlist .modal-overlay')).toHaveCount(0);
    expect(addFired).toBe(false);
  });

  test('first Add shows the interstitial, Confirm fires the wish', async ({ page }) => {
    await page.locator('app-wishlist button.add-wish-btn').click();
    await expect(page.locator('app-wishlist .modal-overlay')).toBeVisible();

    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/wishlist/add'),
      { timeout: 3_000 },
    );
    await page.locator('app-wishlist .modal-content button.confirm-btn').click();
    await req;
    await expect(page.locator('app-wishlist .modal-overlay')).toHaveCount(0);
  });
});

// -------- TV-with-season + non-dismissible media wish + pending transcode --------

test.describe('wishlist — varied row shapes', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await stubWishlistMutations(page);
    await page.route(/\/api\/v2\/wishlist(\?|$)/, route =>
      route.fulfill({ json: wishlistPayload({
        media_wishes: [
          // Non-dismissible row → renders the corner cancel × instead of
          // the bottom Dismiss button.
          {
            id: 7, tmdb_id: 1399, tmdb_title: 'Game of Thrones',
            tmdb_media_type: 'TV', tmdb_poster_path: '/got.jpg',
            tmdb_release_year: 2011, season_number: 4,
            lifecycle_stage: 'ORDERED', lifecycle_label: 'Ordered',
            title_id: null, vote_count: 1, dismissible: false,
          },
          // Dismissible row WITH title_id → click navigates to /title/:id.
          {
            id: 8, tmdb_id: 603, tmdb_title: 'The Matrix',
            tmdb_media_type: 'movie', tmdb_poster_path: '/matrix.jpg',
            tmdb_release_year: 1999, season_number: null,
            lifecycle_stage: 'READY_TO_WATCH', lifecycle_label: 'Ready to watch',
            title_id: 100, vote_count: 1, dismissible: true,
          },
        ],
        transcode_wishes: [
          { id: 5, title_id: 200, title_name: 'Inception',
            poster_url: '/posters/200.jpg', status: 'pending' },
        ],
        album_wishes: [
          { id: 11, release_group_id: 'rg-single', title: 'Kind of Blue',
            primary_artist: 'Miles Davis', year: 1959,
            cover_url: '/posters/w185/301', is_compilation: false },
          { id: 12, release_group_id: 'rg-comp', title: 'Now That\'s What I Call Music!',
            primary_artist: null, year: 2024, cover_url: '/posters/w185/302',
            is_compilation: true },
        ],
      }) }),
    );
    await page.goto('/wishlist');
    await page.waitForSelector('app-wishlist .content-page');
  });

  test('TV media wish shows "TV" type + "Season N" label', async ({ page }) => {
    const card = page.locator('app-wishlist .media-card').first();
    await expect(card.locator('.media-title')).toContainText('Game of Thrones');
    await expect(card.locator('.media-meta')).toContainText('TV');
    await expect(card.locator('.season-label')).toContainText('Season 4');
    // Lifecycle pill carries the server-supplied label.
    await expect(card.locator('.lifecycle-badge')).toContainText('Ordered');
  });

  test('non-dismissible media wish shows the corner × cancel button (no Dismiss button)', async ({ page }) => {
    const card = page.locator('app-wishlist .media-card').first();
    await expect(card.locator('button.cancel-btn')).toBeVisible();
    await expect(card.locator('button.dismiss-btn')).toHaveCount(0);

    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/wishlist/7'),
      { timeout: 3_000 },
    );
    await card.locator('button.cancel-btn').click();
    await req;
  });

  test('dismissible media wish with title_id navigates to the title detail', async ({ page }) => {
    // Card 1 is the Matrix entry (title_id=100, dismissible=true).
    await page.locator('app-wishlist .media-card').nth(1)
      .locator('button.dismiss-btn').click();
    await expect(page).toHaveURL(/\/title\/100$/);
  });

  test('transcode wish with status=pending renders the pending label', async ({ page }) => {
    const row = page.locator('app-wishlist .transcode-list').first().locator('.transcode-row');
    await expect(row.locator('.transcode-status')).toContainText('On NAS, pending desktop');
    await expect(row.locator('.transcode-status')).not.toHaveClass(/ready/);
  });

  test('album wish — single artist subtitle is "Artist · Year"', async ({ page }) => {
    // Album section is the LAST .transcode-list block on the page.
    const sections = page.locator('app-wishlist .transcode-list');
    const albumRows = sections.last().locator('.transcode-row');
    const single = albumRows.first();
    await expect(single.locator('.transcode-title')).toContainText('Kind of Blue');
    await expect(single.locator('.book-wish-author')).toContainText('Miles Davis');
    await expect(single.locator('.book-wish-author')).toContainText('1959');
  });

  test('album wish — compilation subtitle is "Compilation · Year"', async ({ page }) => {
    const albumRows = page.locator('app-wishlist .transcode-list').last().locator('.transcode-row');
    const comp = albumRows.nth(1);
    await expect(comp.locator('.transcode-title')).toContainText('Now That');
    await expect(comp.locator('.book-wish-author')).toContainText('Compilation');
    await expect(comp.locator('.book-wish-author')).toContainText('2024');
  });

  test('album wish remove DELETEs by release_group_id', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/wishlist/albums/rg-single'),
      { timeout: 3_000 },
    );
    await page.locator('app-wishlist .transcode-list').last()
      .locator('.transcode-row').first()
      .locator('button[aria-label="Remove from wish list"]').click();
    await req;
  });
});

// -------- Empty states --------

test.describe('wishlist — empty states', () => {
  test('all-empty wishlist shows the media empty hint + transcode empty hint', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route(/\/api\/v2\/wishlist(\?|$)/, route =>
      route.fulfill({ json: wishlistPayload({ has_any_media_wish: false }) }),
    );
    await page.goto('/wishlist');
    await page.waitForSelector('app-wishlist .content-page');
    await expect(page.locator('app-wishlist').getByText(/No media wishes yet/)).toBeVisible();
    await expect(page.locator('app-wishlist').getByText(/No transcode wishes yet/)).toBeVisible();
    // Book + Album sections aren't rendered at all when their lists are empty.
    await expect(page.locator('app-wishlist h3', { hasText: /^Book Wishes$/ })).toHaveCount(0);
    await expect(page.locator('app-wishlist h3', { hasText: /^Album Wishes$/ })).toHaveCount(0);
  });
});
