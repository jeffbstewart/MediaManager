import { test, expect, Page, Request } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { unframeGrpcWebRequest, fulfillProto } from '../helpers/proto-fixture';
import { create, fromBinary } from '@bufbuild/protobuf';
import {
  ListTitlesRequestSchema,
  TitlePageResponseSchema,
  type TitlePageResponse,
} from '../../src/app/proto-gen/catalog_pb';
import {
  ContentRating,
  MediaType,
  Quality,
} from '../../src/app/proto-gen/common_pb';

// Infinite-scroll behavior on app-title-grid.
//
// title-grid was rewritten to:
//   - Send explicit page+limit on every ListTitles request (was 0/0,
//     which hit the server's 25-item default — only 25 titles ever
//     rendered).
//   - Append page N+1 onto the existing list when an IntersectionObserver
//     on a sentinel <div> below the grid scrolls into view.
//   - Reset back to page 1 (clearing accumulated titles) whenever
//     a sort/filter/playable chip changes.
//
// We mock ListTitles with a per-page dispatcher so we can assert what
// the SPA requests on first paint, on scroll, and after a filter
// change — and that items from later pages are appended to the grid.

const LIST_TITLES_URL = '/mediamanager.CatalogService/ListTitles';
const PAGE_SIZE = 60;
const TOTAL = 130;

/** Build a fake page of movie cards with synthetic ids/names. */
function buildPage(page: number): TitlePageResponse {
  const start = (page - 1) * PAGE_SIZE;
  const end = Math.min(start + PAGE_SIZE, TOTAL);
  const titles = Array.from({ length: end - start }, (_, i) => {
    const id = BigInt(1000 + start + i);
    return {
      id,
      name: `Movie ${start + i + 1}`,
      mediaType: MediaType.MOVIE,
      year: 2000 + ((start + i) % 25),
      posterUrl: undefined,
      contentRating: ContentRating.PG,
      quality: Quality.HD,
      playable: true,
    };
  });
  return create(TitlePageResponseSchema, {
    titles,
    pagination: {
      total: TOTAL,
      page,
      limit: PAGE_SIZE,
      totalPages: Math.ceil(TOTAL / PAGE_SIZE),
    },
    availableRatings: ['G', 'PG', 'PG-13', 'R'],
  });
}

/** Scroll the .page-content container (the actual overflow:auto element
 *  in the shell — not the window) until the sentinel is in view, then
 *  yield a frame so IntersectionObserver can fire its callback. */
async function scrollSentinelIntoView(page: Page): Promise<void> {
  await page.evaluate(async () => {
    const sentinel = document.querySelector('app-title-grid .scroll-sentinel');
    if (!sentinel) return;
    sentinel.scrollIntoView({ block: 'end' });
    // Yield two animation frames so the layout settles and any
    // queued IntersectionObserver callbacks can run.
    await new Promise<void>(r => requestAnimationFrame(() => requestAnimationFrame(() => r())));
  });
}

function decodeListTitles(req: Request) {
  return fromBinary(
    ListTitlesRequestSchema,
    unframeGrpcWebRequest(req.postDataBuffer()),
  );
}

/** Override the default mock-backend handler so each requested page
 *  number returns its own slice of fake titles. Registered AFTER
 *  mockBackend so the LIFO route order picks ours. */
async function mockPagedTitles(page: Page): Promise<void> {
  await page.route(`**${LIST_TITLES_URL}`, async route => {
    const req = fromBinary(
      ListTitlesRequestSchema,
      unframeGrpcWebRequest(route.request().postDataBuffer()),
    );
    await fulfillProto(route, TitlePageResponseSchema, buildPage(req.page || 1));
  });
}

/** Capture every ListTitles request with its decoded page+limit so we
 *  can assert pagination semantics across a test. */
function capturePagedRequests(page: Page): () => { page: number; limit: number; sort: string }[] {
  const seen: { page: number; limit: number; sort: string }[] = [];
  page.on('request', r => {
    if (r.url().endsWith(LIST_TITLES_URL)) {
      const decoded = decodeListTitles(r);
      seen.push({ page: decoded.page, limit: decoded.limit, sort: decoded.sort });
    }
  });
  return () => seen;
}

test.describe('title-grid — infinite scroll', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await mockPagedTitles(page);
    await loginAs(page);
    await stubImages(page);
  });

  test('first page request asks for page=1, limit=60', async ({ page }) => {
    const reqPromise = page.waitForRequest(r => r.url().endsWith(LIST_TITLES_URL),
      { timeout: 5_000 });
    await page.goto('/content/movies');
    const req = await reqPromise;
    const decoded = decodeListTitles(req);
    expect(decoded.page).toBe(1);
    expect(decoded.limit).toBe(PAGE_SIZE);
  });

  test('renders first page (60 cards) on initial load', async ({ page }) => {
    await page.goto('/content/movies');
    await page.waitForSelector('app-title-grid .poster-card');
    const cards = page.locator('app-title-grid .poster-card');
    await expect(cards).toHaveCount(PAGE_SIZE);
    // Total label reflects server total (130), not items rendered.
    await expect(page.locator('app-title-grid .status-label')).toContainText(`${TOTAL} movies`);
  });

  test('scrolling the sentinel into view fetches page 2 and appends', async ({ page }) => {
    await page.goto('/content/movies');
    await page.waitForSelector('app-title-grid .poster-card');
    await expect(page.locator('app-title-grid .poster-card')).toHaveCount(PAGE_SIZE);

    const nextPage = page.waitForRequest(r => {
      if (!r.url().endsWith(LIST_TITLES_URL)) return false;
      return decodeListTitles(r).page === 2;
    }, { timeout: 8_000 });

    // Force the sentinel into view — IntersectionObserver fires
    // loadMore() on visibility (with 400px rootMargin).
    await scrollSentinelIntoView(page);

    const req = await nextPage;
    expect(decodeListTitles(req).limit).toBe(PAGE_SIZE);

    // After page 2 lands we should have 120 cards (60 + 60).
    await expect(page.locator('app-title-grid .poster-card'))
      .toHaveCount(PAGE_SIZE * 2, { timeout: 5_000 });
  });

  test('exhausts after the last page; no extra requests once total reached', async ({ page }) => {
    await page.goto('/content/movies');
    await page.waitForSelector('app-title-grid .poster-card');

    const captured = capturePagedRequests(page);

    // Page 2 (60 -> 120 cards).
    await scrollSentinelIntoView(page);
    await expect(page.locator('app-title-grid .poster-card'))
      .toHaveCount(120, { timeout: 5_000 });

    // Page 3 (120 -> 130 — partial last page).
    await scrollSentinelIntoView(page);
    await expect(page.locator('app-title-grid .poster-card'))
      .toHaveCount(TOTAL, { timeout: 5_000 });

    // Trigger the sentinel a few more times — `hasMore` should be
    // false now, so loadMore() short-circuits and no further request
    // should fire. Wait briefly to give any (incorrect) request
    // time to leave the page.
    for (let i = 0; i < 3; i++) {
      await scrollSentinelIntoView(page);
    }
    await page.waitForTimeout(250);

    // We should see exactly pages 2 and 3, no page 4.
    const pages = captured().map(r => r.page).sort();
    expect(pages).toEqual([2, 3]);
  });

  test('changing sort resets to page 1 and discards prior results', async ({ page }) => {
    await page.goto('/content/movies');
    await page.waitForSelector('app-title-grid .poster-card');

    // Get to page 2 first so the grid has 120 cards accumulated.
    await scrollSentinelIntoView(page);
    await expect(page.locator('app-title-grid .poster-card'))
      .toHaveCount(PAGE_SIZE * 2, { timeout: 5_000 });

    // Click "Year" — should clear the grid and re-issue a page-1
    // request with sort='year'.
    const sortReq = page.waitForRequest(r => {
      if (!r.url().endsWith(LIST_TITLES_URL)) return false;
      const d = decodeListTitles(r);
      return d.page === 1 && d.sort === 'year';
    }, { timeout: 5_000 });
    await page.locator('mat-chip', { hasText: 'Year' }).click();
    await sortReq;

    // Back down to PAGE_SIZE cards (page 1 only).
    await expect(page.locator('app-title-grid .poster-card'))
      .toHaveCount(PAGE_SIZE, { timeout: 5_000 });
  });

  test('toggling Playable resets to page 1', async ({ page }) => {
    await page.goto('/content/movies');
    await page.waitForSelector('app-title-grid .poster-card');

    // Accumulate two pages.
    await scrollSentinelIntoView(page);
    await expect(page.locator('app-title-grid .poster-card'))
      .toHaveCount(PAGE_SIZE * 2, { timeout: 5_000 });

    const reset = page.waitForRequest(r => {
      if (!r.url().endsWith(LIST_TITLES_URL)) return false;
      const d = decodeListTitles(r);
      return d.page === 1 && d.playableOnly === false;
    }, { timeout: 5_000 });
    await page.locator('mat-chip', { hasText: 'Playable' }).first().click();
    await reset;

    await expect(page.locator('app-title-grid .poster-card'))
      .toHaveCount(PAGE_SIZE, { timeout: 5_000 });
  });
});
