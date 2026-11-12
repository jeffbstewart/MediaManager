import type { Page, Route } from '@playwright/test';
import { loadFixture } from './load-fixture';

/**
 * Put the running app into an authenticated state. Works by overriding
 * the `/api/v2/auth/refresh` mock registered in `mockBackend()` — the
 * default returns 401, which makes the auth guard redirect to /login.
 * After `loginAs(page)` the refresh endpoint returns a valid
 * LoginResponse, so `authGuard.tryRefresh()` succeeds and the guarded
 * route loads normally.
 *
 * Must be called AFTER `mockBackend()` (so the base handler exists to
 * override) and BEFORE `page.goto()` on any authenticated route.
 */
export async function loginAs(page: Page): Promise<void> {
  // Later registration wins (Playwright applies routes LIFO), so this
  // shadows the 401-returning refresh from mockBackend().
  await page.route('**/api/v2/auth/refresh', (r: Route) =>
    r.fulfill({ json: loadFixture('auth/login-response.json') })
  );
}
