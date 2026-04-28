import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto } from '../helpers/proto-fixture';
import { create } from '@bufbuild/protobuf';
import { TagDetailSchema } from '../../src/app/proto-gen/common_pb';

const CS = '/mediamanager.CatalogService';

// Tag list (/content/tags) + tag detail (/tag/:id) functional tests.
//
// /content/tags renders <app-tags>: a flat .tag-chip pill grid with
// no admin-only controls — the same affordances are visible to
// viewers and admins. Selecting a chip routes to /tag/:id.
//
// /tag/:id renders <app-tag-detail>: header pill, a poster grid of
// titles, optionally a tagged-tracks list at the bottom, and (for
// admins only) a search-titles input that POSTs the chosen result
// to /api/v2/catalog/tags/:id/titles/:titleId.
//
// Default fixtures: catalog/tags.list.json (3 tags) and
// catalog/tag-detail.json (one Movie + one TV title, no tracks).
// Tests that need richer rows (book, album, tracks) override the
// detail GET inline.

const VIEWER = { features: 'viewer' as const };
const ADMIN  = { features: 'admin' as const };

test.describe('tag list — viewer', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page, VIEWER);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/content/tags');
    await page.waitForSelector('app-tags .tag-grid');
  });

  test('renders the three tag chips with name, count, and color', async ({ page }) => {
    const chips = page.locator('app-tags a.tag-chip');
    await expect(chips).toHaveCount(3);

    await expect(chips.nth(0).locator('.tag-name')).toContainText('Comfort Watch');
    await expect(chips.nth(0).locator('.tag-count')).toContainText('(12)');
    await expect(chips.nth(0)).toHaveAttribute('style', /background-color:\s*rgb\(27,\s*94,\s*32\)/i);

    await expect(chips.nth(1).locator('.tag-name')).toContainText('For Rainy Days');
    await expect(chips.nth(1).locator('.tag-count')).toContainText('(7)');

    await expect(chips.nth(2).locator('.tag-name')).toContainText('Hidden Gem');
    await expect(chips.nth(2).locator('.tag-count')).toContainText('(4)');
  });

  test('total label reflects tag count', async ({ page }) => {
    await expect(page.locator('app-tags .status-label')).toContainText('3 tags');
  });

  test('clicking a tag chip navigates to /tag/:id', async ({ page }) => {
    await page.locator('app-tags a.tag-chip').first().click();
    await expect(page).toHaveURL(/\/tag\/1$/);
  });
});

// -------- Tag detail — viewer --------

test.describe('tag detail — viewer', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page, VIEWER);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/tag/1');
    await page.waitForSelector('app-tag-detail .header');
  });

  test('header renders tag name + title count using fixture colors', async ({ page }) => {
    const badge = page.locator('app-tag-detail .header .tag-badge');
    await expect(badge).toContainText('Comfort Watch');
    await expect(badge).toHaveAttribute('style', /background-color:\s*rgb\(27,\s*94,\s*32\)/i);
    await expect(page.locator('app-tag-detail .title-count')).toContainText('2 titles');
  });

  test('renders the title poster grid (Movie + TV)', async ({ page }) => {
    const cards = page.locator('app-tag-detail .poster-card');
    await expect(cards).toHaveCount(2);
    await expect(cards.first().locator('.poster-title')).toContainText('The Matrix');
    await expect(cards.first().locator('.media-type-pill')).toContainText('Movie');
    await expect(cards.first().locator('.poster-meta')).toContainText('1999');
    await expect(cards.nth(1).locator('.media-type-pill')).toContainText('TV');
  });

  test('progress overlay renders on partly-watched titles', async ({ page }) => {
    // Breaking Bad fixture has progress_fraction=0.62.
    await expect(page.locator('app-tag-detail .poster-card').nth(1).locator('.progress-fill'))
      .toHaveAttribute('style', /width:\s*62%/);
  });

  test('clicking a poster card navigates to /title/:id', async ({ page }) => {
    await page.locator('app-tag-detail .poster-card').first().click();
    await expect(page).toHaveURL(/\/title\/100$/);
  });

  test('admin-only affordances are NOT rendered for viewers', async ({ page }) => {
    // No add-input, no admin-add-row, no per-card remove buttons.
    await expect(page.locator('app-tag-detail .add-input')).toHaveCount(0);
    await expect(page.locator('app-tag-detail .admin-add-row')).toHaveCount(0);
    await expect(page.locator('app-tag-detail button.remove-btn')).toHaveCount(0);
  });

  test('tagged-tracks section is hidden when tracks list is empty', async ({ page }) => {
    await expect(page.locator('app-tag-detail .tagged-tracks-section')).toHaveCount(0);
  });
});

// -------- Tag detail — tagged-tracks section --------

test.describe('tag detail — tagged tracks', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page, VIEWER);
    await loginAs(page);
    await stubImages(page);
    await page.route(`**${CS}/GetTagDetail`, r =>
      fulfillProto(r, TagDetailSchema, create(TagDetailSchema, {
        name: 'Workout',
        color: { hex: '#222222' },
        titles: [],
        tracks: [
          {
            id: 9001n, name: 'So What', titleId: 301n, playable: true,
            duration: { seconds: 565 }, titleName: 'Kind of Blue',
            trackArtists: [{ id: 1n, name: 'Miles Davis' }],
          },
          {
            id: 9002n, name: 'Freddie Freeloader', titleId: 301n, playable: true,
            duration: { seconds: 589 }, titleName: 'Kind of Blue',
            trackArtists: [{ id: 1n, name: 'Miles Davis' }],
          },
        ],
      })),
    );
    await page.goto('/tag/1');
    await page.waitForSelector('app-tag-detail .tagged-tracks-section');
  });

  test('renders track rows with name, album · artist, duration', async ({ page }) => {
    const rows = page.locator('app-tag-detail li.tagged-track');
    await expect(rows).toHaveCount(2);
    await expect(rows.first().locator('.track-name')).toContainText('So What');
    // Album name + artist composite line: "Kind of Blue · Miles Davis"
    await expect(rows.first().locator('.track-album')).toContainText('Kind of Blue');
    await expect(rows.first().locator('.track-artist')).toContainText('Miles Davis');
    await expect(rows.first().locator('.track-duration')).toContainText('9:25');
  });

  test('clicking the album link navigates to /title/:id', async ({ page }) => {
    await page.locator('app-tag-detail li.tagged-track').first()
      .locator('a.track-album').click();
    await expect(page).toHaveURL(/\/title\/301$/);
  });

  test('per-row Play button kicks off audio playback', async ({ page }) => {
    await page.locator('app-tag-detail li.tagged-track').first()
      .locator('button[aria-label="Play this track"]').click();
    await expect(page.locator('.audio-player.visible')).toBeVisible({ timeout: 3_000 });
  });

  test('Play all kicks off audio playback', async ({ page }) => {
    await page.locator('app-tag-detail .tagged-tracks-header button', { hasText: 'Play all' }).click();
    await expect(page.locator('.audio-player.visible')).toBeVisible({ timeout: 3_000 });
  });
});

// -------- Tag detail — admin add-to-tag search --------

async function setupAdmin(page: Page) {
  await mockBackend(page, ADMIN);
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/catalog/tags/1/titles/*', route => {
    if (['POST', 'DELETE'].includes(route.request().method())) {
      return route.fulfill({ json: { ok: true } });
    }
    return route.fallback();
  });
  // Search returns one row of every media type the catalog produces.
  await page.route('**/api/v2/catalog/tags/1/search-titles*', route =>
    route.fulfill({ json: { results: [
      { title_id: 100, title_name: 'The Matrix',     release_year: 1999, media_type: 'MOVIE' },
      { title_id: 200, title_name: 'Breaking Bad',   release_year: 2008, media_type: 'TV'    },
      { title_id: 300, title_name: 'Dune',           release_year: 1965, media_type: 'BOOK'  },
      { title_id: 301, title_name: 'Kind of Blue',   release_year: 1959, media_type: 'ALBUM' },
    ] } })
  );
}

test.describe('tag detail — admin add-to-tag search', () => {
  test.beforeEach(async ({ page }) => {
    await setupAdmin(page);
    await page.goto('/tag/1');
    await page.waitForSelector('app-tag-detail input.add-input');
  });

  test('admin sees the search input with the descriptive placeholder + aria-label', async ({ page }) => {
    const input = page.locator('app-tag-detail input.add-input');
    await expect(input).toHaveAttribute('placeholder', /Search the catalog for titles to add/);
    await expect(input).toHaveAttribute('aria-label', /Search the catalog for titles to add/);
  });

  test('typing 2+ characters fires GET /search-titles?q=...', async ({ page }) => {
    const req = page.waitForRequest(r =>
      /\/api\/v2\/catalog\/tags\/1\/search-titles/.test(r.url())
        && /q=ma/i.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-tag-detail input.add-input').fill('ma');
    await req;
  });

  test('typing 1 character does NOT fire a search', async ({ page }) => {
    let fired = false;
    page.on('request', r => {
      if (/\/search-titles/.test(r.url())) fired = true;
    });
    await page.locator('app-tag-detail input.add-input').fill('m');
    // Give Angular a microtask + a frame to make sure no debounce fires.
    await page.evaluate(() => new Promise<void>(resolve =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
    ));
    expect(fired).toBe(false);
  });

  test('search results render one row per media type with the right pill', async ({ page }) => {
    await page.locator('app-tag-detail input.add-input').fill('test');
    await page.waitForSelector('app-tag-detail .search-results .search-result-row');
    const rows = page.locator('app-tag-detail .search-result-row');
    await expect(rows).toHaveCount(4);
    await expect(rows.nth(0)).toContainText('The Matrix');
    await expect(rows.nth(0).locator('.result-media-type')).toContainText('Movie');
    await expect(rows.nth(1).locator('.result-media-type')).toContainText('TV');
    await expect(rows.nth(2).locator('.result-media-type')).toContainText('Book');
    await expect(rows.nth(3).locator('.result-media-type')).toContainText('Album');
  });

  // One test per media type proves the same Add affordance carries the
  // right title_id into the POST regardless of what kind of thing the
  // user is tagging. Each click also triggers a refresh GET — assert
  // that too so we know the list re-syncs.
  for (const c of [
    { label: 'Movie', rowIdx: 0, titleId: 100 },
    { label: 'TV',    rowIdx: 1, titleId: 200 },
    { label: 'Book',  rowIdx: 2, titleId: 300 },
    { label: 'Album', rowIdx: 3, titleId: 301 },
  ]) {
    test(`Add on a ${c.label} POSTs /titles/${c.titleId} and refreshes the tag`, async ({ page }) => {
      await page.locator('app-tag-detail input.add-input').fill('test');
      await page.waitForSelector('app-tag-detail .search-results .search-result-row');

      const added = page.waitForRequest(r =>
        r.method() === 'POST'
          && r.url().endsWith(`/api/v2/catalog/tags/1/titles/${c.titleId}`),
        { timeout: 3_000 },
      );
      const refreshed = page.waitForRequest(r =>
        r.url().endsWith(`${CS}/GetTagDetail`),
        { timeout: 3_000 },
      );
      await page.locator('app-tag-detail .search-result-row').nth(c.rowIdx)
        .locator('button.add-btn').click();
      await added;
      await refreshed;
      // Search dropdown clears after a successful add.
      await expect(page.locator('app-tag-detail .search-results')).toHaveCount(0);
      await expect(page.locator('app-tag-detail input.add-input')).toHaveValue('');
    });
  }

  test('admin sees the per-card remove button on title cards', async ({ page }) => {
    await expect(page.locator('app-tag-detail .poster-card button.remove-btn')).toHaveCount(2);
  });

  test('clicking a per-card remove button DELETEs /titles/:id and refreshes', async ({ page }) => {
    const removed = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/catalog/tags/1/titles/100'),
      { timeout: 3_000 },
    );
    // Card click navigates by default — the component already calls
    // preventDefault + stopPropagation inside the click handler, so a
    // bare .click() is enough to fire the remove without leaving the
    // page.
    await page.locator('app-tag-detail .poster-card').first()
      .locator('button.remove-btn').click();
    await removed;
  });
});
