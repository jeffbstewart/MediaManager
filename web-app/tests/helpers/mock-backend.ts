import type { Page, Route } from '@playwright/test';
import { loadFixture } from './load-fixture';

/**
 * Backend-mock options. Each key toggles one endpoint's default response.
 * Individual tests can override any endpoint with a per-test `page.route()`
 * registered AFTER `mockBackend()` — Playwright applies route handlers in
 * LIFO order, so later registrations win.
 */
export interface MockBackendOptions {
  /** What `/api/v2/auth/discover` returns — controls login vs setup branching. */
  discover?: 'normal' | 'setup-required';
  /** Legal-compliance status returned by `/api/v2/legal/status`. */
  legalStatus?: 'compliant' | 'pending';
  /** Feature-flags fixture served from `/api/v2/catalog/features`. */
  features?: 'viewer' | 'admin';
  /** Home-feed fixture served from `/api/v2/catalog/home`. */
  homeFeed?: 'populated' | 'empty';
}

/**
 * Install the default set of `page.route()` handlers so every Tier 1 page
 * can render without a real backend. Helpers that come online in later
 * tiers (catalog endpoints, admin endpoints, image stubs) plug in here
 * with no call-site changes in the tests.
 *
 * Must be called BEFORE `page.goto(...)` — Playwright's route handlers
 * only intercept requests issued after they're registered.
 */
export async function mockBackend(page: Page, opts: MockBackendOptions = {}): Promise<void> {
  const discoverFixture = opts.discover === 'setup-required'
    ? 'auth/discover.setup-required.json'
    : 'auth/discover.normal.json';
  const legalFixture = opts.legalStatus === 'pending'
    ? 'legal/status.pending.json'
    : 'legal/status.compliant.json';

  // --- Auth ---
  await page.route('**/api/v2/auth/discover', (r: Route) =>
    r.fulfill({ json: loadFixture(discoverFixture) })
  );

  await page.route('**/api/v2/auth/login', (r: Route) =>
    r.fulfill({ json: loadFixture('auth/login-response.json') })
  );

  // Silent refresh: 401 until the test explicitly grants a session via
  // loginAs(). Login page uses this during ngOnInit to skip the form if
  // a cookie is already live.
  await page.route('**/api/v2/auth/refresh', (r: Route) =>
    r.fulfill({ status: 401, json: { error: 'no refresh cookie' } })
  );

  await page.route('**/api/v2/auth/logout', (r: Route) =>
    r.fulfill({ status: 204 })
  );

  // Setup endpoint: shape matches LoginResponse.
  await page.route('**/api/v2/auth/setup', (r: Route) =>
    r.fulfill({ json: loadFixture('auth/login-response.json') })
  );

  // --- Legal ---
  await page.route('**/api/v2/legal/status', (r: Route) =>
    r.fulfill({ json: loadFixture(legalFixture) })
  );

  await page.route('**/api/v2/legal/agree', (r: Route) =>
    r.fulfill({ status: 204 })
  );

  // --- Profile (change-password) ---
  await page.route('**/api/v2/profile/change-password', (r: Route) =>
    r.fulfill({ json: { ok: true } })
  );

  // --- Catalog ---
  const featuresFixture = opts.features === 'admin'
    ? 'catalog/features.admin.json'
    : 'catalog/features.viewer.json';
  const homeFixture = opts.homeFeed === 'empty'
    ? 'catalog/home.empty.json'
    : 'catalog/home.populated.json';

  await page.route('**/api/v2/catalog/features', (r: Route) =>
    r.fulfill({ json: loadFixture(featuresFixture) })
  );
  await page.route('**/api/v2/catalog/home', (r: Route) =>
    r.fulfill({ json: loadFixture(homeFixture) })
  );
}
