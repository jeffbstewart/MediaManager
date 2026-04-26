import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Top-bar interaction test. Three groups under test:
//
//   1. Sidenav toggle  — hamburger flips mat-sidenav between open/closed.
//   2. Search box      — debounced suggestions, Enter-to-search,
//                        × clear, advanced (tune) shortcut.
//   3. Profile menu    — open trigger and the four menu items
//                        (Profile, Help, Report a Problem, Sign Out).
//
// All assertions run against the mocked backend; nothing touches Kotlin.
test.describe('top bar', () => {

  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    // Wait for the toolbar — once it's there the rest of the chrome is
    // mounted (search input, profile button, sidenav).
    await page.waitForSelector('mat-toolbar.top-toolbar');
  });

  // -------- Sidenav toggle --------

  test('hamburger toggles the sidenav', async ({ page }) => {
    const sidenav = page.locator('mat-sidenav.shell-drawer');
    // Desktop default is open — drawerOpen signal initialises from
    // !mobileQuery.matches, and Playwright's default viewport is
    // desktop-wide.
    await expect(sidenav).toHaveClass(/mat-drawer-opened/);

    await page.locator('button[aria-label="Toggle navigation"]').click();
    await expect(sidenav).not.toHaveClass(/mat-drawer-opened/);

    await page.locator('button[aria-label="Toggle navigation"]').click();
    await expect(sidenav).toHaveClass(/mat-drawer-opened/);
  });

  // -------- Search box --------

  test('typing in search shows suggestions after debounce', async ({ page }) => {
    await page.locator('.search-input').fill('test');
    // Debounce is 250 ms inside onSearchInput; the suggestions
    // dropdown appears once the catalog.search() promise resolves.
    const dropdown = page.locator('.suggestions-dropdown');
    await expect(dropdown).toBeVisible({ timeout: 2_000 });
    // search.results.json starts with The Matrix; the See-all-results
    // row is also present and points at /search?q=test.
    await expect(dropdown.locator('.suggestion-item').first()).toContainText('The Matrix');
    await expect(dropdown.locator('.suggestion-all'))
      .toHaveAttribute('href', /\/search\?q=test/);
  });

  test('Enter in search navigates to /search?q=...', async ({ page }) => {
    await page.locator('.search-input').fill('matrix');
    await page.locator('.search-input').press('Enter');
    await expect(page).toHaveURL(/\/search\?q=matrix/);
  });

  test('× clears the search input', async ({ page }) => {
    const input = page.locator('.search-input');
    await input.fill('matrix');
    await expect(page.locator('.search-clear')).toBeVisible();

    // mousedown handler fires before the input loses focus, so a
    // normal click works the same as the production interaction.
    await page.locator('.search-clear').click();
    await expect(input).toHaveValue('');
    // The clear button hides itself when the input is empty
    // (@if (searchQuery().length > 0) guard).
    await expect(page.locator('.search-clear')).toHaveCount(0);
  });

  test('Advanced search button opens the advanced-search dialog', async ({ page }) => {
    // The shell navigates to /search?advanced=1, but search.ts
    // immediately replaceUrl()s away from it once the dialog opens.
    // The user-visible signal is the dialog mounted in the CDK
    // overlay; assert that, not the transient URL.
    await page.locator('.search-advanced').click();
    await expect(page.locator('app-advanced-search-dialog')).toBeVisible({ timeout: 2_000 });
    await expect(page).toHaveURL(/\/search(\?|$)/);
  });

  test('Advanced search carries the current query into the dialog URL', async ({ page }) => {
    await page.locator('.search-input').fill('matrix');
    await page.locator('.search-advanced').click();
    // After the strip, ?advanced=1 is gone but ?q=matrix survives.
    await expect(page.locator('app-advanced-search-dialog')).toBeVisible({ timeout: 2_000 });
    await expect(page).toHaveURL(/\/search\?q=matrix$/);
  });

  // -------- Profile menu --------

  test('profile menu opens with the four expected items', async ({ page }) => {
    await page.locator('button[aria-label="Profile menu"]').click();
    // mat-menu renders the panel into the CDK overlay container, not
    // inside the shell. Items are <a mat-menu-item> / <button mat-menu-item>;
    // text is the user-facing label.
    const panel = page.locator('.mat-mdc-menu-panel');
    await expect(panel).toBeVisible();
    for (const label of ['Profile', 'Help', 'Report a Problem', 'Sign Out']) {
      await expect(panel.locator('.mat-mdc-menu-item', { hasText: label })).toHaveCount(1);
    }
  });

  test('Profile menu item → /profile', async ({ page }) => {
    await page.locator('button[aria-label="Profile menu"]').click();
    await page.locator('.mat-mdc-menu-panel .mat-mdc-menu-item', { hasText: 'Profile' }).click();
    await expect(page).toHaveURL(/\/profile$/);
  });

  test('Help menu item → /help', async ({ page }) => {
    await page.locator('button[aria-label="Profile menu"]').click();
    await page.locator('.mat-mdc-menu-panel .mat-mdc-menu-item', { hasText: 'Help' }).click();
    await expect(page).toHaveURL(/\/help$/);
  });

  test('Report a Problem opens the problem-report dialog', async ({ page }) => {
    await page.locator('button[aria-label="Profile menu"]').click();
    await page.locator('.mat-mdc-menu-panel .mat-mdc-menu-item', { hasText: 'Report a Problem' }).click();
    // Dialog renders inline in the shell template (NOT in the CDK
    // overlay) and gates on a signal; its h3 is the most stable
    // marker independent of styling churn.
    await expect(page.locator('app-report-problem-dialog h3', { hasText: 'Report a Problem' }))
      .toBeVisible({ timeout: 2_000 });
  });

  test('Sign Out fires POST /auth/logout', async ({ page }) => {
    // The post-logout navigation lands at /login briefly, but the
    // login page calls auth.tryRefresh() on mount and our loginAs()
    // stub still says authenticated → it bounces back to /. The
    // logout POST is the durable, observable signal that the menu
    // item did its job.
    const loggedOut = page.waitForRequest(req =>
      req.method() === 'POST' && req.url().endsWith('/api/v2/auth/logout'),
    );
    await page.locator('button[aria-label="Profile menu"]').click();
    await page.locator('.mat-mdc-menu-panel .mat-mdc-menu-item', { hasText: 'Sign Out' }).click();
    await loggedOut;
  });
});
