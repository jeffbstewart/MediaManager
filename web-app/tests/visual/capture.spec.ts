import { basename, resolve } from 'node:path';
import { argosScreenshot } from '@argos-ci/playwright';
import { test } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// index.html loads fonts from /app/vendor/fonts/, which `ng serve`
// proxies to a backend that isn't running under the mocked suites, so
// the dev server answers 404 and text falls back to a serif. Axe doesn't
// care; screenshots do. Serve the vendored files straight from disk.
// Playwright runs with web-app/ as the working directory (the config
// lives there), so a cwd-relative path is stable; import.meta is avoided
// because it would switch this spec to ESM and break the CommonJS helpers.
const FONTS_DIR = resolve(process.cwd(), 'public', 'vendor', 'fonts');
const FONT_TYPES: Record<string, string> = { '.css': 'text/css', '.woff2': 'font/woff2' };

// Visual capture: screenshots of the main views at the two reference
// widths, per theme, against the mocked backend with generated artwork.
// Not a pass/fail suite and not run by the harness; `capture.mjs` runs
// it and sets VISUAL_OUT. Each capture goes through Argos's helper,
// which waits for fonts and images and freezes animations. Without the
// Argos reporter (desk runs, cloud sandboxes) the PNGs land in VISUAL_OUT
// for humans; with it (the `visual` job in .github/workflows/ci.yml,
// ARGOS_UPLOAD=1) they are uploaded and compared with the merge-base
// build, and Argos posts the result on the pull request.
//
// Theme selection pre-seeds the key ThemeService reads before bootstrap
// (decision 3 in docs/plans/web-theming.md), and the mocked profile
// reports the same theme as its default so the choice survives the
// profile load.

const OUT = process.env.VISUAL_OUT;
const THEMES = (process.env.VISUAL_THEMES ?? 'marquee').split(',').map(s => s.trim()).filter(Boolean);

const VIEWS: ReadonlyArray<{ name: string; path: string; ready: string }> = [
  { name: 'home', path: '/', ready: 'app-home' },
  { name: 'movies', path: '/content/movies', ready: 'app-title-grid, app-movies' },
  { name: 'title', path: '/title/100', ready: 'app-title-detail' },
  { name: 'profile', path: '/profile', ready: 'app-profile' },
  { name: 'live-tv', path: '/live-tv', ready: 'app-live-tv' },
  { name: 'discover', path: '/discover', ready: 'app-discover .new-seasons app-wide-card' },
  // Added after the Grids chain: these pages changed in rows 04 to 06
  // with no capture to show it.
  { name: 'search', path: '/search?q=test', ready: 'app-search .results-grid' },
  { name: 'wishlist', path: '/wishlist', ready: 'app-wishlist .content-page' },
  { name: 'collections', path: '/content/collections', ready: 'app-collections .grid' },
  { name: 'tags', path: '/content/tags', ready: 'app-tags .grid' },
];

const WIDTHS: ReadonlyArray<{ width: number; height: number }> = [
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
];

// Argos readies each page with an inline script (page.addScriptTag), which
// the production Content-Security-Policy that test-fixture serves blocks
// under script-src 'self'. The capture is not the CSP check; the functional
// specs for the same views are. So Chromium ignores the policy here: the
// header is still sent, just not enforced.
test.use({ bypassCSP: true });

test.describe('visual capture', () => {
  test.skip(!OUT, 'Set VISUAL_OUT (capture.mjs does) to run the visual capture.');

  for (const theme of THEMES) {
    for (const view of VIEWS) {
      for (const vp of WIDTHS) {
        test(`${view.name} ${vp.width} ${theme}`, async ({ page }) => {
          await page.addInitScript((t: string) => {
            try { localStorage.setItem('mm.theme.effective', t); } catch { /* storage blocked */ }
          }, theme);
          await page.setViewportSize(vp);
          // Both themes are dark only (plan decision S2); sample them that way.
          await page.emulateMedia({ colorScheme: 'dark' });
          await page.route('**/app/vendor/fonts/*', (r) => {
            const file = basename(new URL(r.request().url()).pathname);
            const ext = file.slice(file.lastIndexOf('.'));
            const contentType = FONT_TYPES[ext];
            if (!contentType) return r.fulfill({ status: 404 });
            return r.fulfill({ path: resolve(FONTS_DIR, file), contentType });
          });
          await mockBackend(page, { features: 'viewer', homeFeed: 'populated', defaultTheme: theme });
          await loginAs(page);
          await stubImages(page, { generatedArt: true });
          await page.goto(view.path);
          await page.waitForSelector(view.ready, { state: 'attached' });
          await page.waitForLoadState('networkidle');
          // Let fonts and any lazy images settle before sampling.
          await page.evaluate(() => (document as Document & { fonts: FontFaceSet }).fonts.ready);
          await page.waitForTimeout(250);
          // Full page for home and title: nothing on them loads more on
          // scroll, and their lower sections (the home rows, the title's
          // cast and Similar Titles) sit below the fold at both widths.
          // The grids and the guide do load more on scroll, so they stay
          // viewport-only.
          await argosScreenshot(page, `${view.name}-${vp.width}-${theme}`, {
            root: OUT,
            fullPage: view.name === 'home' || view.name === 'title',
          });
        });
      }
    }
  }
});
