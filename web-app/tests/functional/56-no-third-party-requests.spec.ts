import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Privacy regression test. Walks the major surfaces of the SPA and
// asserts every browser-issued request stays on our origin. Any
// regression that reintroduces a `<img src="https://image.tmdb.org/...">`
// or similar will fail here, before it ships.
//
// Allowed exceptions (project rule):
//   - Privacy policy / terms-of-use anchors: rendered as
//     <a target="_blank">; opening them is user-initiated, not a
//     page-load fetch. We don't navigate them in this test.
//   - Video / audio / live-tv stream URLs: same-origin already.
//
// Anything else hitting a non-origin host fails the test.

const ROUTES = [
  '/',
  '/content/movies',
  '/content/tv',
  '/content/books',
  '/content/music',
  '/content/collections',
  '/content/family',
  '/content/playlists',
  '/content/tags',
  '/wishlist',
  '/discover',
  '/search?q=test',
  '/title/100',
  '/title/200',
  '/title/300',
  '/title/301',
  '/actor/6384',
  '/artist/1',
  '/author/1',
  '/cameras',
  '/live-tv',
];

test.describe('privacy: no third-party requests', () => {
  for (const route of ROUTES) {
    test(`every request from ${route} stays on our origin`, async ({ page, baseURL }) => {
      await mockBackend(page);
      await loginAs(page);
      await stubImages(page);

      const ourHost = new URL(baseURL ?? page.url() ?? 'http://localhost:4200/').host;
      const offenders: string[] = [];
      page.on('request', req => {
        const url = req.url();
        // about:blank, data:, blob: don't count — they're handled
        // entirely in-process by the browser. Same with chrome-
        // extension:// from devtools (won't appear in CI but is
        // safe to allowlist if it does).
        if (/^(about:|data:|blob:|chrome-extension:|chrome:)/i.test(url)) return;
        try {
          const u = new URL(url);
          // Same-origin: pass.
          if (u.host === ourHost) return;
          // Localhost in tests is "127.0.0.1" sometimes vs
          // "localhost" — treat the IPv4 + IPv6 + name forms as
          // identical to the page host's hostname for this gate.
          const aliases = new Set([ourHost, ourHost.split(':')[0]]);
          if (aliases.has(u.host) || aliases.has(u.hostname)) return;
          offenders.push(url);
        } catch {
          offenders.push(url);
        }
      });

      await page.goto(route);
      // Give Angular + lazy chunks a moment to settle. Most pages
      // fire their initial RPCs within the first few hundred ms;
      // the test deliberately doesn't wait for individual selectors
      // because we want to capture EVERY request, including ones
      // from background tasks that fire after the first paint.
      await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {});

      expect(offenders, `Off-origin requests from ${route}:\n  ${offenders.join('\n  ')}`)
        .toEqual([]);
    });
  }
});
