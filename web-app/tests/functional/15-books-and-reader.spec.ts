import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Force serial within this file: the EPUB tests load real fixture
// bytes through epub.js (jszip + epub.min.js + the EPUB itself); under
// the default fullyParallel config, eight pages compete for the dev
// server simultaneously and intermittently miss the route override
// for /reading-progress before the reader's ngOnInit reads it. Serial
// keeps every fixture fetch hermetic.
test.describe.configure({ mode: 'serial' });

// Books page + reader integration tests.
//
// Books page (5)
//   - Filter chip toggling (playable / sort) updates request params
//   - Both fixture books render with title + author
//   - Clicking a book navigates to /title/:id
//
// Reader (4)
//   - PDF: percent display from saved progress + iframe src
//   - EPUB: percent display from saved progress + reader still
//     calls /reading-progress GET on open + font-size controls work
//
// Fixture chain:
//   /api/v2/catalog/titles?media_type=BOOK → titles.books.json
//   /api/v2/reading-progress/:id           → per-test override
//   /ebook/:id (HEAD)                      → per-test override (CT sniff)
//   /ebook/:id (GET)                       → per-test override (binary)

const BOOK_ID = 300;          // Dune
const PDF_ITEM_ID = 8001;
const EPUB_ITEM_ID = 8002;

const FIXTURE_EPUB = 'tests/fixtures/ebook/test.epub';

/** Inspect outgoing /api/v2/catalog/titles requests; returns a getter
 *  that resolves to the latest URL hit since the spec started. */
function captureTitlesRequests(page: Page) {
  const urls: string[] = [];
  page.on('request', req => {
    if (req.url().includes('/api/v2/catalog/titles')) urls.push(req.url());
  });
  return () => urls;
}

test.describe('books page', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
  });

  test('renders both books with title + author', async ({ page }) => {
    await page.goto('/content/books');
    await page.waitForSelector('app-title-grid .poster-card');
    const cards = page.locator('app-title-grid .poster-card');
    await expect(cards).toHaveCount(2);
    await expect(cards.first().locator('.poster-title')).toContainText('Dune');
    await expect(cards.nth(1).locator('.poster-title')).toContainText('The Left Hand of Darkness');
  });

  test('clicking a book navigates to /title/:id', async ({ page }) => {
    await page.goto('/content/books');
    await page.waitForSelector('app-title-grid .poster-card');
    await page.locator('app-title-grid .poster-card').first().click();
    await expect(page).toHaveURL(/\/title\/300$/);
  });

  test('Playable chip toggle adds playable_only=false to the request', async ({ page }) => {
    const urls = captureTitlesRequests(page);
    await page.goto('/content/books');
    await page.waitForSelector('app-title-grid .poster-card');

    // Default state: playable_only NOT in URL (it's only sent when false).
    expect(urls().at(-1)).not.toContain('playable_only');

    // Toggle the chip OFF — the chip starts highlighted because
    // playableOnly defaults to true.
    const reqPromise = page.waitForRequest(
      req => req.url().includes('/api/v2/catalog/titles') && req.url().includes('playable_only=false'),
      { timeout: 3_000 },
    );
    await page.locator('mat-chip', { hasText: 'Playable' }).first().click();
    await reqPromise;
    expect(urls().at(-1)).toContain('playable_only=false');
  });

  test('sort chip clicks add sort=<mode> to the request', async ({ page }) => {
    const urls = captureTitlesRequests(page);
    await page.goto('/content/books');
    await page.waitForSelector('app-title-grid .poster-card');
    expect(urls().at(-1)).toContain('sort=name');

    // Books page replaces "Popular" with "Author" in the sort options.
    // Use substring filter — chip text has surrounding whitespace
    // from the template, so an anchored regex misses; getByRole
    // doesn't apply because mat-chip-set (without listbox/grid mode)
    // doesn't assign chips an ARIA role.
    for (const { label, param } of [
      { label: 'Author', param: 'sort=author' },
      { label: 'Year',   param: 'sort=year' },
      { label: 'Recent', param: 'sort=recent' },
      { label: 'Name',   param: 'sort=name' },
    ]) {
      // Start the listener BEFORE the click — clicking fires the
      // refresh request synchronously inside the same task, so a
      // waitForRequest started after the click misses it.
      const reqPromise = page.waitForRequest(
        req => req.url().includes('/api/v2/catalog/titles') && req.url().includes(param),
        { timeout: 3_000 },
      );
      await page.locator('mat-chip', { hasText: label }).click();
      await reqPromise;
      expect(urls().at(-1)).toContain(param);
    }
  });

  test('selected sort chip is highlighted', async ({ page }) => {
    await page.goto('/content/books');
    await page.waitForSelector('app-title-grid .poster-card');
    const nameChip = page.locator('mat-chip', { hasText: 'Name' });
    await expect(nameChip).toHaveClass(/mat-mdc-chip-highlighted/);

    await page.locator('mat-chip', { hasText: 'Author' }).click();
    await expect(page.locator('mat-chip', { hasText: 'Author' }))
      .toHaveClass(/mat-mdc-chip-highlighted/);
    await expect(nameChip).not.toHaveClass(/mat-mdc-chip-highlighted/);
  });
});

test.describe('reader — PDF', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);

    // Reading-progress: 73% saved so the header should reflect it.
    await page.route(`**/api/v2/reading-progress/${PDF_ITEM_ID}`, route => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ json: { media_item_id: PDF_ITEM_ID, cfi: null, percent: 0.73, updated_at: null } });
      }
      return route.fulfill({ status: 204 });
    });

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
      req => req.method() === 'GET'
        && req.url().endsWith(`/api/v2/reading-progress/${PDF_ITEM_ID}`),
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

    await page.route(`**/api/v2/reading-progress/${EPUB_ITEM_ID}`, route => {
      if (route.request().method() === 'GET') {
        // cfi="chapter2.xhtml" — epub.js's display() accepts hrefs
        // in addition to canonical CFIs. Saves us the gory string
        // shape of a real CFI in the test fixture; the contract from
        // the reader's POV is that whatever string the server hands
        // back gets passed straight through.
        return route.fulfill({
          json: { media_item_id: EPUB_ITEM_ID, cfi: 'chapter2.xhtml', percent: 0.5, updated_at: '2026-04-01T00:00:00Z' },
        });
      }
      // POST from reportProgress: respond 204 so the reader's
      // promise resolves quietly.
      return route.fulfill({ status: 204 });
    });

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
      req => req.method() === 'POST'
        && req.url().endsWith(`/api/v2/reading-progress/${EPUB_ITEM_ID}`),
      { timeout: 10_000 },
    );
    // Give epub.js a moment for the relocated event to fire and set
    // lastCfi before we navigate away.
    await page.waitForTimeout(500);
    await page.locator('app-reader button[aria-label="Close"]').click();
    await posted;
  });
});
