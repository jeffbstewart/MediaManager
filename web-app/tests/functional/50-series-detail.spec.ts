import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// /series/:id — SeriesComponent. Book series detail with hero,
// owned-volumes grid, missing-volumes grid + per-volume wish heart,
// and a "Wishlist all missing" bulk action.

async function setup(page: Page) {
  await mockBackend(page);
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/wishlist/books', r =>
    r.fulfill({ json: { ok: true } }));
  await page.route('**/api/v2/wishlist/books/*', r => {
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.route('**/api/v2/catalog/series/*/wishlist-gaps', r =>
    r.fulfill({ json: { added: 3, already_wished: 1 } }));
  await page.goto('/series/1');
  await page.waitForSelector('app-series .hero');
}

test.describe('series detail — hero + owned volumes', () => {
  test('renders title + author link + collection size', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-series h1')).toContainText('Dune');
    await expect(page.locator('app-series .known-for a', { hasText: 'Frank Herbert' }))
      .toHaveAttribute('href', '/author/1');
    await expect(page.locator('app-series .lifespan')).toContainText('1 volume');
  });

  test('owned-volumes grid renders fixture rows + links to /title/:id', async ({ page }) => {
    await setup(page);
    const cards = page.locator('app-series .poster-grid').first().locator('.poster-card');
    await expect(cards).toHaveCount(1);
    await expect(cards.first()).toContainText('Dune');
    await expect(cards.first()).toHaveAttribute('href', '/title/300');
  });
});

test.describe('series detail — missing volumes', () => {
  test('section renders when can_fill_gaps + has missing volumes', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-series h2', { hasText: 'Missing Volumes' })).toBeVisible();
    // Default fixture has one missing volume (Dune Messiah, OL12345W).
    await expect(page.locator('app-series .poster-card.unowned')).toHaveCount(1);
    await expect(page.locator('app-series .poster-card.unowned')).toContainText('Dune Messiah');
  });

  test('un-wished missing volume click POSTs /wishlist/books with the volume payload', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/wishlist/books'),
      { timeout: 3_000 },
    );
    await page.locator('app-series .poster-card.unowned button.wish-btn').click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({
      ol_work_id: 'OL12345W',
      title: 'Dune Messiah',
      author: 'Frank Herbert',
      series_id: 1,
      series_number: '2',
    });
  });

  test('wished volume click DELETEs the wish', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route('**/api/v2/wishlist/books/*', r => {
      if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
      return r.fallback();
    });
    await page.route('**/api/v2/catalog/series/*', r =>
      r.fulfill({ json: {
        id: 1, name: 'Dune', poster_url: '/posters/300', author: { id: 1, name: 'Frank Herbert' },
        volumes: [], can_fill_gaps: true,
        missing_volumes: [{
          ol_work_id: 'OL999W', title: 'God Emperor of Dune', series_number: '4',
          year: 1981, cover_url: null, already_wished: true,
        }],
      } }));
    await page.goto('/series/1');
    await page.waitForSelector('app-series .hero');
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/wishlist/books/OL999W'),
      { timeout: 3_000 },
    );
    await page.locator('app-series .poster-card.unowned button.wish-btn').click();
    await req;
  });

  test('Wishlist-all-missing fires POST /wishlist-gaps and renders the result message', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && /\/wishlist-gaps$/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-series button', { hasText: 'Wishlist all missing' }).click();
    await req;
    await expect(page.locator('app-series .muted'))
      .toContainText('Added 3 books');
    await expect(page.locator('app-series .muted'))
      .toContainText('1 already there');
  });
});

test.describe('series detail — gap-filling unavailable', () => {
  test('renders the helpful hint when can_fill_gaps=false', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route('**/api/v2/catalog/series/*', r =>
      r.fulfill({ json: {
        id: 1, name: 'Unknown Series', poster_url: null,
        author: { id: 1, name: 'Author' },
        volumes: [{ title_id: 100, title_name: 'V1', series_number: '1', poster_url: null, release_year: 2000 }],
        missing_volumes: [], can_fill_gaps: false,
      } }));
    await page.goto('/series/1');
    await page.waitForSelector('app-series .hero');
    await expect(page.locator('app-series .muted'))
      .toContainText("don't know this series");
  });
});

test.describe('series detail — error state', () => {
  test('failed series load shows the error message', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route('**/api/v2/catalog/series/*', r => r.fulfill({ status: 500 }));
    await page.goto('/series/1');
    await expect(page.locator('app-series .error-message'))
      .toContainText('Failed to load');
  });
});
