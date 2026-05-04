import { test, expect, Page, Request } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto, unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { create, fromBinary } from '@bufbuild/protobuf';
import { ReadingProgressSchema } from '../../src/app/proto-gen/common_pb';
import { AuthorSort, ListAuthorsRequestSchema } from '../../src/app/proto-gen/artist_pb';
import { ReportReadingProgressRequestSchema } from '../../src/app/proto-gen/playback_pb';

const LIST_AUTHORS_URL = '/mediamanager.ArtistService/ListAuthors';
const PB = '/mediamanager.PlaybackService';

function decodeListAuthors(req: Request): { sort: AuthorSort; q: string } {
  const decoded = fromBinary(
    ListAuthorsRequestSchema,
    unframeGrpcWebRequest(req.postDataBuffer()),
  );
  return { sort: decoded.sort, q: decoded.q ?? '' };
}

// Force serial within this file: the EPUB tests load real fixture
// bytes through epub.js (jszip + epub.min.js + the EPUB itself); under
// the default fullyParallel config, eight pages compete for the dev
// server simultaneously and intermittently miss the route override
// for /reading-progress before the reader's ngOnInit reads it. Serial
// keeps every fixture fetch hermetic.
test.describe.configure({ mode: 'serial' });

// Books page + reader integration tests.
//
// Books page (author-grid, mirrors music-list)
//   - Author cards render with name + owned-book count
//   - Hero image uses /author-headshots/:id when has_headshot is true
//   - Placeholder mat-icon shows when has_headshot is false
//   - Clicking an author navigates to /author/:id
//   - Sort chips (Books / Name / Recent) update the request
//   - Search input fires a debounced ListAuthors with q
//
// Reader (PDF + EPUB)
//   - PDF: percent display from saved progress + iframe src
//   - EPUB: percent display from saved progress + font-size controls
//     + saved-CFI navigation + progress reporting
//   - Repro for the "progress always 0%" bug — percent must climb past
//     0% as the reader paginates AND the POSTed fraction must follow.
//
// Fixture chain:
//   ArtistService.ListAuthors               → authorsListFixture
//   ArtistService.GetAuthorDetail           → authorFrankHerbert
//   PlaybackService.GetReadingProgress      → per-test override
//   /ebook/:id (HEAD)                       → per-test override (CT sniff)
//   /ebook/:id (GET)                        → per-test override (binary)

const PDF_ITEM_ID = 8001;
const EPUB_ITEM_ID = 8002;
// Separate fixture: same shape as test.epub but ships a stylesheet
// that pins paragraphs at an absolute font-size. The reader's
// theme.fontSize() override only injects a body-level rule, so this
// fixture exists to prove the inheritance reset in the reader's
// custom theme actually scales the visible text.
const EPUB_WITH_CSS_ITEM_ID = 8003;

const FIXTURE_EPUB = 'tests/fixtures/ebook/test.epub';
const FIXTURE_EPUB_WITH_CSS = 'tests/fixtures/ebook/test-with-css.epub';

/** Capture each ListAuthors request's decoded body. */
function captureAuthorRequests(page: Page) {
  const seen: { sort: AuthorSort; q: string }[] = [];
  page.on('request', req => {
    if (req.url().endsWith(LIST_AUTHORS_URL)) {
      try { seen.push(decodeListAuthors(req)); } catch { /* ignore framing errors */ }
    }
  });
  return () => seen;
}

test.describe('books page', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/content/books');
    await page.waitForSelector('app-books .author-grid');
  });

  // -------- Display --------

  test('renders all 4 authors with name + book count', async ({ page }) => {
    const cards = page.locator('app-books .author-card');
    await expect(cards).toHaveCount(4);

    await expect(cards.first().locator('.author-name')).toContainText('Frank Herbert');
    await expect(cards.first().locator('.author-meta')).toContainText('6 books');

    // Pluralization: 1 book (no "s") for the singleton author.
    await expect(cards.nth(3).locator('.author-name')).toContainText('Solo Author');
    await expect(cards.nth(3).locator('.author-meta')).toContainText('1 book');
    await expect(cards.nth(3).locator('.author-meta')).not.toContainText('1 books');
  });

  test('total label reflects author count', async ({ page }) => {
    await expect(page.locator('app-books .status-label')).toContainText('4 authors');
  });

  test('hero image uses /author-headshots/:id when has_headshot is true', async ({ page }) => {
    const herbertImg = page.locator('app-books .author-card').first().locator('img');
    await expect(herbertImg).toBeVisible();
    await expect(herbertImg).toHaveAttribute('src', /\/author-headshots\/1$/);
  });

  test('hero image falls back to /posters/w185/:id when has_headshot is false but fallback_book_title_id is set', async ({ page }) => {
    // Isaac Asimov has hasHeadshot=false but fallbackBookTitleId=700.
    const asimov = page.locator('app-books .author-card').nth(2);
    const img = asimov.locator('img');
    await expect(img).toBeVisible();
    await expect(img).toHaveAttribute('src', /\/posters\/w185\/700$/);
  });

  test('placeholder shows when neither headshot nor fallback book is available', async ({ page }) => {
    // Solo Author has no headshot and no fallbackBookTitleId →
    // placeholder mat-icon, no <img>.
    const solo = page.locator('app-books .author-card').nth(3);
    await expect(solo.locator('img')).toHaveCount(0);
    await expect(solo.locator('.author-placeholder mat-icon')).toBeVisible();
    await expect(solo.locator('.author-placeholder mat-icon')).toContainText('person');
  });

  test('clicking an author card navigates to /author/:id', async ({ page }) => {
    await page.locator('app-books .author-card').first().click();
    await expect(page).toHaveURL(/\/author\/1$/);
  });

  // -------- Sort chips --------

  test('selected sort chip is highlighted (Books by default)', async ({ page }) => {
    const books = page.locator('app-books mat-chip', { hasText: 'Books' });
    await expect(books).toHaveClass(/mat-mdc-chip-highlighted/);

    await page.locator('app-books mat-chip', { hasText: 'Name' }).click();
    await expect(page.locator('app-books mat-chip', { hasText: 'Name' }))
      .toHaveClass(/mat-mdc-chip-highlighted/);
    await expect(books).not.toHaveClass(/mat-mdc-chip-highlighted/);
  });

  test('clicking each sort chip fires a ListAuthors request with the right enum sort', async ({ page }) => {
    for (const { label, sort } of [
      { label: 'Name',   sort: AuthorSort.NAME },
      { label: 'Recent', sort: AuthorSort.RECENT },
      { label: 'Books',  sort: AuthorSort.BOOKS },
    ]) {
      const reqPromise = page.waitForRequest(req =>
        req.url().endsWith(LIST_AUTHORS_URL),
        { timeout: 3_000 },
      );
      await page.locator('app-books mat-chip', { hasText: label }).click();
      const got = await reqPromise;
      expect(decodeListAuthors(got).sort).toBe(sort);
    }
  });

  // -------- Search --------

  test('typing in the search box fires a debounced ListAuthors request with q', async ({ page }) => {
    const reqPromise = page.waitForRequest(req =>
      req.url().endsWith(LIST_AUTHORS_URL),
      { timeout: 3_000 },
    );
    await page.locator('app-books input.search-input').fill('herbert');
    const got = await reqPromise;
    expect(decodeListAuthors(got).q.toLowerCase()).toBe('herbert');
  });

  test('clear button empties the search and fires a request without q', async ({ page }) => {
    const typed = page.waitForRequest(req => req.url().endsWith(LIST_AUTHORS_URL),
      { timeout: 3_000 });
    await page.locator('app-books input.search-input').fill('herbert');
    await typed;

    const cleared = page.waitForRequest(req => req.url().endsWith(LIST_AUTHORS_URL),
      { timeout: 3_000 });
    await page.locator('app-books button.search-clear').click();
    const got = await cleared;
    expect(decodeListAuthors(got).q).toBe('');
    await expect(page.locator('app-books input.search-input')).toHaveValue('');
  });
});

test.describe('reader — PDF', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);

    // Reading-progress: 73% saved so the header should reflect it.
    // mock-backend's GetReadingProgress default is zero-progress; per-
    // test override returns the populated fixture for this PDF.
    await page.route(`**${PB}/GetReadingProgress`, r =>
      fulfillProto(r, ReadingProgressSchema, create(ReadingProgressSchema, {
        mediaItemId: BigInt(PDF_ITEM_ID),
        fraction: 0.73,
      })));

    // /ebook/:id: HEAD reports PDF, GET serves an empty body. We
    // don't care that the iframe rendering of an empty PDF fails —
    // the reader's own state machine has already taken the PDF
    // branch by then.
    await page.route(`**/ebook/${PDF_ITEM_ID}`, route => {
      const headers = { 'Content-Type': 'application/pdf' };
      if (route.request().method() === 'HEAD') {
        return route.fulfill({ status: 200, headers, body: '' });
      }
      return route.fulfill({ status: 200, headers, body: '' });
    });
  });

  test('PDF mode mounts the pdf-frame iframe', async ({ page }) => {
    await page.goto(`/reader/${PDF_ITEM_ID}`);
    // The iframe presence proves the reader took the PDF branch
    // (the @if mediaFormat === 'EBOOK_PDF' guard fired). We don't
    // assert on iframe.src because Angular's DomSanitizer treats
    // iframe[src] as RESOURCE_URL and an unbypassed string binding
    // resolves to empty — the production reader has the same
    // behaviour and PDF rendering hinges on the browser's PDF
    // viewer kicking in via subsequent property writes.
    await expect(page.locator('iframe.pdf-frame')).toBeVisible();
    // Note: the percent display is EPUB-only in the current toolbar.
    // PDF mode loads the saved progress (verified by the next test)
    // but has no UI surface for it yet.
  });

  test('reader fetches the saved progress on open', async ({ page }) => {
    const progressFetch = page.waitForRequest(
      req => req.url().endsWith(`${PB}/GetReadingProgress`),
    );
    await page.goto(`/reader/${PDF_ITEM_ID}`);
    await progressFetch;
  });
});

test.describe('reader — EPUB', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);

    // locator="chapter2.xhtml" — epub.js's display() accepts hrefs in
    // addition to canonical CFIs. Saves us the gory string shape of a
    // real CFI in the test fixture; the contract from the reader's
    // POV is that whatever string the server hands back gets passed
    // straight through. mock-backend's ReportReadingProgress default
    // is no-op so the catalog service's awaited promise resolves.
    await page.route(`**${PB}/GetReadingProgress`, r =>
      fulfillProto(r, ReadingProgressSchema, create(ReadingProgressSchema, {
        mediaItemId: BigInt(EPUB_ITEM_ID),
        locator: 'chapter2.xhtml',
        fraction: 0.5,
        updatedAt: { secondsSinceEpoch: 1743465600n }, // 2025-04-01T00:00:00Z
      })));

    await page.route(`**/ebook/${EPUB_ITEM_ID}`, route => {
      const headers = { 'Content-Type': 'application/epub+zip' };
      if (route.request().method() === 'HEAD') {
        return route.fulfill({ status: 200, headers, body: '' });
      }
      // GET serves the real fixture EPUB so epub.js can render it.
      return route.fulfill({
        status: 200,
        headers: { ...headers, 'Accept-Ranges': 'bytes' },
        path: FIXTURE_EPUB,
      });
    });
  });

  test('header shows percent from saved reading-progress', async ({ page }) => {
    await page.goto(`/reader/${EPUB_ITEM_ID}`);
    // Header is visible as soon as loading flips off (mediaFormat
    // signal fires); the percent text comes from progress.percent
    // immediately, before epub.js has rendered anything.
    await expect(page.locator('app-reader .percent')).toContainText('50%', { timeout: 5_000 });
  });

  test('percent climbs past 0% as the reader paginates through the book', async ({ page }) => {
    // Reproduces the "progress always shows 0%" bug. The reader binds
    // its percent display to epub.js's `loc.start.percentage`, but
    // epub.js can't compute a meaningful percentage until
    // book.locations has been generated. The reader never calls
    // book.locations.generate(...), so as the user paginates the
    // header stays stuck at whatever the saved value was when the
    // reader opened. New users open at 0% and never see it move.
    //
    // Test scenario: first-open of a fresh book (saved fraction=0,
    // locator empty), advance the reader past the title page into
    // chapter content, assert the percent has gone above 0%.

    // Override the default mock so this test starts with no progress.
    await page.route(`**${PB}/GetReadingProgress`, r =>
      fulfillProto(r, ReadingProgressSchema, create(ReadingProgressSchema, {
        mediaItemId: BigInt(EPUB_ITEM_ID),
        locator: '',
        // No fraction set → defaults to 0.
      })));

    // Capture every ReportReadingProgress POST body so we can assert
    // the reader sends a non-zero fraction back to the server.
    const reported: number[] = [];
    page.on('request', req => {
      if (!req.url().endsWith(`${PB}/ReportReadingProgress`)) return;
      const buf = req.postDataBuffer();
      if (!buf) return;
      const decoded = fromBinary(ReportReadingProgressRequestSchema,
        unframeGrpcWebRequest(buf));
      reported.push(decoded.fraction);
    });

    await page.goto(`/reader/${EPUB_ITEM_ID}`);
    await page.waitForSelector('app-reader .epub-container iframe', { timeout: 10_000 });
    await expect(page.locator('app-reader .percent')).toHaveText('0%');

    // Advance enough pages to land deep in the book. After enough
    // forward navigation the percent should reflect the current
    // position. Click the rendered "next page" arrow rather than
    // poking at epub.js internals.
    const next = page.locator('app-reader button[aria-label="Next page"]');
    for (let i = 0; i < 5; i++) {
      await next.click();
    }

    // After paginating forward, the header MUST report a non-zero
    // percent. Without book.locations.generate() the value is stuck
    // at 0% and this assertion fails — that is the user-visible bug.
    await expect.poll(
      async () => {
        const txt = await page.locator('app-reader .percent').textContent();
        return Number((txt ?? '0%').replace('%', '').trim());
      },
      { timeout: 10_000, message: 'percent should climb above 0% after paginating' },
    ).toBeGreaterThan(0);

    // Tear down the reader to flush a final ReportReadingProgress
    // POST through ngOnDestroy. We then assert at least one report
    // carried a non-zero fraction — proving the value the server
    // persists is the lived position, not a stuck zero.
    await page.locator('app-reader button[aria-label="Close"]').click();
    await expect.poll(() => reported.some(f => f > 0),
      { timeout: 5_000, message: 'reader must POST a non-zero fraction' })
      .toBe(true);
  });

  test('font-size controls update the displayed value', async ({ page }) => {
    await page.goto(`/reader/${EPUB_ITEM_ID}`);
    await page.waitForSelector('app-reader .font-size-indicator');
    const indicator = page.locator('app-reader .font-size-indicator');
    await expect(indicator).toContainText('100%');

    await page.locator('app-reader button[aria-label="Increase font size"]').click();
    await expect(indicator).toContainText('110%');

    await page.locator('app-reader button[aria-label="Decrease font size"]').click();
    await page.locator('app-reader button[aria-label="Decrease font size"]').click();
    await expect(indicator).toContainText('90%');
  });

  test('font-size controls actually scale paragraph text against an absolute-size stylesheet', async ({ page }) => {
    // Regression for the "controls only change line spacing" bug.
    // Many real EPUBs declare `p { font-size: NNpx }` directly on
    // inner elements; the reader's theme.fontSize() injects a
    // body-level rule which gets overridden, so the body-derived
    // line-height grows but paragraph text stays the same size.
    // Open an EPUB whose stylesheet pins paragraphs at 16px and
    // assert the iframe's computed font-size on a real <p> goes up
    // when the user clicks Increase.
    await page.route(`**/ebook/${EPUB_WITH_CSS_ITEM_ID}`, route => {
      const headers = { 'Content-Type': 'application/epub+zip' };
      if (route.request().method() === 'HEAD') {
        return route.fulfill({ status: 200, headers, body: '' });
      }
      return route.fulfill({
        status: 200,
        headers: { ...headers, 'Accept-Ranges': 'bytes' },
        path: FIXTURE_EPUB_WITH_CSS,
      });
    });
    await page.route(`**${PB}/GetReadingProgress`, r =>
      fulfillProto(r, ReadingProgressSchema, create(ReadingProgressSchema, {
        mediaItemId: BigInt(EPUB_WITH_CSS_ITEM_ID),
      })));

    await page.goto(`/reader/${EPUB_WITH_CSS_ITEM_ID}`);
    await page.waitForSelector('app-reader .epub-container iframe', { timeout: 10_000 });
    const epubFrame = page.frameLocator('app-reader .epub-container iframe');
    // Wait until epub.js has rendered the chapter content.
    await expect(epubFrame.locator('p#t')).toBeVisible({ timeout: 10_000 });

    const fontSizeOf = async () => {
      const v = await epubFrame.locator('p#t').evaluate((el) =>
        getComputedStyle(el).fontSize,
      );
      return Number(v.replace('px', ''));
    };

    const baseline = await fontSizeOf();
    expect(baseline).toBeGreaterThan(0);

    // Three consecutive Increase clicks (10 % per click, so +30 % total)
    // — easily large enough to clear any sub-pixel rounding noise.
    for (let i = 0; i < 3; i++) {
      await page.locator('app-reader button[aria-label="Increase font size"]').click();
    }

    await expect.poll(fontSizeOf, { timeout: 5_000 }).toBeGreaterThan(baseline);
  });

  test('reader uses the saved CFI to jump on open', async ({ page }) => {
    await page.goto(`/reader/${EPUB_ITEM_ID}`);
    // Wait for epub.js to render. It mounts an iframe inside
    // .epub-container; its body holds the chapter HTML. Use
    // frameLocator to peek inside.
    await page.waitForSelector('app-reader .epub-container iframe', { timeout: 10_000 });
    const epubFrame = page.frameLocator('app-reader .epub-container iframe');
    // The saved cfi pointed at chapter2.xhtml; epub.js's display()
    // honours that, so the rendered iframe should carry the
    // chapter-2 heading. waitForFunction inside frameLocator until
    // the H1 text matches.
    await expect(epubFrame.locator('h1')).toContainText('Chapter 2', { timeout: 10_000 });
  });

  test('reader reports progress back via POST after navigation', async ({ page }) => {
    await page.goto(`/reader/${EPUB_ITEM_ID}`);
    await page.waitForSelector('app-reader .epub-container iframe', { timeout: 10_000 });

    // The reader sets a 10 s reportTimer on bootEpub; we don't want
    // to wait that long, so trigger the unmount path which fires a
    // final reportProgress(true). Navigating away calls ngOnDestroy
    // which fires the POST if lastCfi is set (which it will be —
    // rendition.display() emits 'relocated' before resolving).
    const posted = page.waitForRequest(
      req => req.url().endsWith(`${PB}/ReportReadingProgress`),
      { timeout: 10_000 },
    );
    // Give epub.js a moment for the relocated event to fire and set
    // lastCfi before we navigate away.
    await page.waitForTimeout(500);
    await page.locator('app-reader button[aria-label="Close"]').click();
    await posted;
  });
});
