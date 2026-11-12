import { basename, resolve } from 'node:path';
import type { Page } from '@playwright/test';
import { test } from '../helpers/test-fixture';
import { mockBackend, type MockBackendOptions } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { PLAYER_TRANSCODE_ID, setupPlayerRoutes } from '../helpers/player-stubs';

// Documentation screenshots (docs/images/screenshots), taken from the
// mocked app so they can be published: the fixtures' real titles and
// people are swapped for invented names (tests/helpers/fictional-names.ts,
// MM_FICTIONAL_NAMES=1) and every poster, backdrop, cover, and headshot
// is generated abstract artwork with no id printed on it. Not a
// pass/fail suite and not run by the harness; `docs.mjs` runs it with
// DOCS_OUT set and the two environment variables above.
//
// Fonts: index.html loads them from /app/vendor/fonts/, which `ng serve`
// proxies to a backend that is not running here, so serve the vendored
// files from disk (same as capture.spec.ts).

const OUT = process.env.DOCS_OUT;
const FONTS_DIR = resolve(process.cwd(), 'public', 'vendor', 'fonts');
const FONT_TYPES: Record<string, string> = { '.css': 'text/css', '.woff2': 'font/woff2' };

interface Shot {
  file: string;
  path: string;
  /** A selector that proves the page rendered its content. */
  ready: string;
  /** Mock options; `null` means no login (the auth pages). */
  mock: MockBackendOptions | null;
  fullPage?: boolean;
  /** Extra setup after the mocks are in place and before navigation. */
  before?: (page: Page) => Promise<void>;
  /** Interaction after the page is ready and before the screenshot. */
  act?: (page: Page) => Promise<void>;
}

const viewer: MockBackendOptions = { features: 'viewer', homeFeed: 'populated' };
const admin: MockBackendOptions = { features: 'admin', homeFeed: 'populated' };

const SHOTS: ReadonlyArray<Shot> = [
  { file: 'login.png', path: '/login', ready: 'app-login form', mock: null },
  { file: 'setup-wizard.png', path: '/setup', ready: 'app-setup form', mock: null },
  { file: 'home.png', path: '/', ready: 'app-home app-poster-card', mock: viewer, fullPage: true },
  // The README shows the home page above the fold; the guide shows all of it.
  { file: 'home-top.png', path: '/', ready: 'app-home app-poster-card', mock: viewer },
  { file: 'home-empty.png', path: '/', ready: 'app-home', mock: { ...viewer, homeFeed: 'empty' } },
  { file: 'catalog.png', path: '/content/movies', ready: 'app-title-grid app-poster-card', mock: viewer },
  { file: 'catalog-tv.png', path: '/content/tv', ready: 'app-title-grid app-poster-card', mock: viewer },
  { file: 'search.png', path: '/search?q=harbor', ready: 'app-search .results-grid', mock: viewer },
  { file: 'title-detail-movie.png', path: '/title/100', ready: 'app-title-detail .similar-row', mock: viewer, fullPage: true },
  { file: 'title-detail-tv.png', path: '/title/200', ready: 'app-title-detail .similar-row', mock: viewer, fullPage: true },
  {
    file: 'player.png',
    path: `/play/${PLAYER_TRANSCODE_ID}?title=Harbor%20Lights`,
    ready: 'app-player video',
    mock: viewer,
    before: page => setupPlayerRoutes(page),
    act: async page => {
      // Let the first frame paint, then move the mouse so the controls show.
      await page.waitForFunction(() => {
        const v = document.querySelector('video') as HTMLVideoElement | null;
        return !!v && v.readyState >= 2;
      }, undefined, { timeout: 15_000 });
      const box = await page.locator('app-player video').boundingBox();
      if (box) await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.waitForTimeout(300);
    },
  },
  { file: 'wishlist.png', path: '/wishlist', ready: 'app-wishlist .media-card', mock: viewer, fullPage: true },
  { file: 'profile.png', path: '/profile', ready: 'app-profile .quality-row', mock: viewer },
  { file: 'settings.png', path: '/admin/settings', ready: 'app-settings .settings-page', mock: admin, fullPage: true },
  { file: 'users.png', path: '/admin/users', ready: 'app-users table', mock: admin },
  { file: 'transcode-status.png', path: '/admin/transcodes/status', ready: 'app-transcode-status .status-card', mock: admin },
  { file: 'purchase-wishes.png', path: '/admin/purchase-wishes', ready: 'app-purchase-wishes table', mock: admin, fullPage: true },
];

test.describe('docs screenshots', () => {
  test.skip(!OUT, 'Set DOCS_OUT (docs.mjs does) to write the documentation screenshots.');
  test.skip(process.env.MM_FICTIONAL_NAMES !== '1', 'Set MM_FICTIONAL_NAMES=1 (docs.mjs does): published screenshots use invented names.');

  for (const shot of SHOTS) {
    test(shot.file, async ({ page }) => {
      await page.setViewportSize({ width: 1280, height: 800 });
      await page.emulateMedia({ colorScheme: 'dark' });
      await page.route('**/app/vendor/fonts/*', (r) => {
        const file = basename(new URL(r.request().url()).pathname);
        const ext = file.slice(file.lastIndexOf('.'));
        const contentType = FONT_TYPES[ext];
        if (!contentType) return r.fulfill({ status: 404 });
        return r.fulfill({ path: resolve(FONTS_DIR, file), contentType });
      });
      if (shot.mock) {
        await mockBackend(page, shot.mock);
        await loginAs(page);
      } else {
        await mockBackend(page, { discover: shot.file === 'setup-wizard.png' ? 'setup-required' : 'normal' });
      }
      await stubImages(page, { generatedArt: { label: false } });
      if (shot.before) await shot.before(page);
      await page.goto(shot.path);
      await page.waitForSelector(shot.ready, { state: 'attached' });
      await page.waitForLoadState('networkidle');
      await page.evaluate(() => (document as Document & { fonts: FontFaceSet }).fonts.ready);
      await page.waitForTimeout(300);
      if (shot.act) await shot.act(page);
      await page.screenshot({ path: resolve(OUT!, shot.file), fullPage: !!shot.fullPage });
    });
  }
});
