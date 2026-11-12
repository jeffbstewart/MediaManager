import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { expectView } from '../helpers/expect-view';

// Top-bar interaction test. Three groups under test:
//
//   1. Navigation      — fixed side nav at desktop width; below 1024px
//                        the hamburger opens the same nav in a left
//                        <dialog> sheet that closes on navigation.
//      Header         — frosted `is-scrolled` state follows the
//                        document scroll.
//   2. Search box      — debounced suggestions, Enter-to-search,
//                        × clear, advanced (tune) shortcut, and the
//                        phone-width fold to an icon.
//   3. User sheet      — account pill, the <dialog> sheet with its four
//                        links (Profile, Help, Report a Problem,
//                        Sign Out), and the close rules (link,
//                        backdrop, Escape, close button).
//
// All assertions run against the mocked backend; nothing touches Kotlin.
test.describe('top bar', () => {

  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    // Wait for the header — once it's there the rest of the chrome is
    // mounted (search input, account pill, side nav).
    await page.waitForSelector('header.app-header');
  });

  // -------- Navigation --------

  test('at desktop width the side nav is shown and the hamburger is not', async ({ page }) => {
    await expect(page.locator('aside.side-nav nav[aria-label="Primary"]')).toBeVisible();
    await expect(page.locator('button[aria-label="Open navigation"]')).toBeHidden();
    // The nav sheet renders no second nav while closed.
    await expect(page.locator('nav[aria-label="Primary"]')).toHaveCount(1);
  });

  test('the side nav is a translucent scrim so the page glow runs under it', async ({ page }) => {
    const style = await page.locator('aside.side-nav').evaluate(el => {
      const cs = getComputedStyle(el);
      return { background: cs.backgroundColor, backdrop: cs.backdropFilter };
    });
    // "rgba(0, 0, 0, 0.3)" -> 0.3; an opaque "rgb(r, g, b)" has no fourth part.
    const parts = style.background.replace(/^rgba?\(|\)$/g, '').split(',').map(Number);
    const alpha = parts[3] ?? 1;
    expect(alpha).toBeLessThan(1);
    expect(style.backdrop).toContain('blur(');
    // The glow spans the full page width, so the nav sits over it rather than beside it.
    const glow = (await page.locator('.page-glow').boundingBox())!;
    expect(glow.x).toBe(0);
    expect(glow.width).toBeGreaterThanOrEqual(page.viewportSize()!.width - 20);
  });

  test('below 1024px the hamburger opens the nav sheet', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.locator('aside.side-nav')).toBeHidden();
    const toggle = page.locator('button[aria-label="Open navigation"]');
    await expect(toggle).toBeVisible();
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');

    await toggle.click();
    const sheet = page.locator('dialog.sheet.left[aria-label="Navigation"]');
    await expect(sheet).toBeVisible();
    expect(await sheet.evaluate(d => d.matches(':modal'))).toBe(true);
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(sheet.locator('nav[aria-label="Primary"] a.nav-item', { hasText: 'Movies' })).toBeVisible();
  });

  test('a nav sheet link navigates and closes the sheet', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('button[aria-label="Open navigation"]').click();
    const sheet = page.locator('dialog.sheet.left');
    await sheet.locator('a.nav-item', { hasText: 'Collections' }).click();
    await expectView(page, /\/collections$/, 'app-collections');
    await expect(sheet).toBeHidden();
    await expect(page.locator('button[aria-label="Open navigation"]')).toHaveAttribute('aria-expanded', 'false');
  });

  test('Escape, the close button and a backdrop click close the nav sheet', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const toggle = page.locator('button[aria-label="Open navigation"]');
    const sheet = page.locator('dialog.sheet.left');

    await toggle.click();
    await expect(sheet).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(sheet).toBeHidden();

    await toggle.click();
    await sheet.locator('button[aria-label="Close"]').click();
    await expect(sheet).toBeHidden();

    await toggle.click();
    await expect(sheet).toBeVisible();
    // The sheet hugs the left edge; the right of the viewport is backdrop.
    await page.mouse.click(370, 400);
    await expect(sheet).toBeHidden();
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
    await expectView(page, /\/search\?q=matrix/, 'app-search');
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
    await expectView(page, /\/search(\?|$)/, 'app-search');
  });

  test('Advanced search carries the current query into the dialog URL', async ({ page }) => {
    await page.locator('.search-input').fill('matrix');
    await page.locator('.search-advanced').click();
    // After the strip, ?advanced=1 is gone but ?q=matrix survives.
    await expect(page.locator('app-advanced-search-dialog')).toBeVisible({ timeout: 2_000 });
    await expectView(page, /\/search\?q=matrix$/, 'app-search');
  });

  // -------- Header frosting --------

  test('header frosts once the page scrolls and clears at the top', async ({ page }) => {
    // A short viewport guarantees the home page overflows the document.
    await page.setViewportSize({ width: 1280, height: 320 });
    await page.waitForSelector('app-home');
    const header = page.locator('header.app-header');
    await expect(header).not.toHaveClass(/is-scrolled/);

    await page.evaluate(() => window.scrollTo(0, 200));
    await expect(header).toHaveClass(/is-scrolled/);

    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(header).not.toHaveClass(/is-scrolled/);
  });

  // -------- Phone search --------

  test('at phone width the search pill folds to an icon that reveals it', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/');
    await page.waitForSelector('header.app-header');
    const toggle = page.locator('button.search-toggle');
    const input = page.locator('.search-input');
    await expect(toggle).toBeVisible();
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(input).toBeHidden();

    await toggle.click();
    await expect(input).toBeVisible();
    await expect(input).toBeFocused();
    await expect(toggle).toBeHidden();

    // Escape clears and folds it back up.
    await input.fill('matrix');
    await input.press('Escape');
    await expect(input).toBeHidden();
    await expect(toggle).toBeVisible();
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  test('at phone width a submitted search still navigates', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/');
    await page.waitForSelector('header.app-header');
    await page.locator('button.search-toggle').click();
    await page.locator('.search-input').fill('matrix');
    await page.locator('.search-input').press('Enter');
    await expectView(page, /\/search\?q=matrix/, 'app-search');
  });

  test('at desktop width the search icon button is not shown', async ({ page }) => {
    await expect(page.locator('button.search-toggle')).toBeHidden();
    await expect(page.locator('.search-input')).toBeVisible();
  });

  // -------- User sheet --------

  test('account pill shows the avatar initial and username', async ({ page }) => {
    const pill = page.locator('button[aria-label="Open user menu"]');
    // profile/profile.json: username "testuser".
    await expect(pill.locator('.avatar')).toHaveText('T');
    await expect(pill.locator('.label')).toHaveText('testuser');
    await expect(pill).toHaveAttribute('aria-haspopup', 'dialog');
    await expect(pill).toHaveAttribute('aria-expanded', 'false');
  });

  test('user sheet opens as a modal dialog with the four expected items', async ({ page }) => {
    const pill = page.locator('button[aria-label="Open user menu"]');
    await pill.click();
    const sheet = page.locator('dialog.sheet[aria-label="User menu"]');
    await expect(sheet).toBeVisible();
    await expect(pill).toHaveAttribute('aria-expanded', 'true');
    // showModal() puts the dialog in the top layer.
    expect(await sheet.evaluate(d => d.matches(':modal'))).toBe(true);
    await expect(sheet.locator('.who b')).toHaveText('testuser');
    await expect(sheet.locator('.who')).toContainText('Viewer');
    for (const label of ['Profile', 'Help', 'Report a Problem', 'Sign Out']) {
      await expect(sheet.locator('.sheet-link', { hasText: label })).toHaveCount(1);
    }
  });

  test('Escape closes the user sheet', async ({ page }) => {
    const pill = page.locator('button[aria-label="Open user menu"]');
    await pill.click();
    const sheet = page.locator('dialog.sheet[aria-label="User menu"]');
    await expect(sheet).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(sheet).toBeHidden();
    await expect(pill).toHaveAttribute('aria-expanded', 'false');
  });

  test('a backdrop click closes the user sheet', async ({ page }) => {
    await page.locator('button[aria-label="Open user menu"]').click();
    const sheet = page.locator('dialog.sheet[aria-label="User menu"]');
    await expect(sheet).toBeVisible();
    // The sheet hugs the right edge; the left of the viewport is backdrop.
    await page.mouse.click(40, 300);
    await expect(sheet).toBeHidden();
  });

  test('the close button closes the user sheet', async ({ page }) => {
    await page.locator('button[aria-label="Open user menu"]').click();
    const sheet = page.locator('dialog.sheet[aria-label="User menu"]');
    await sheet.locator('button[aria-label="Close"]').click();
    await expect(sheet).toBeHidden();
  });

  test('a click inside the sheet panel keeps it open', async ({ page }) => {
    await page.locator('button[aria-label="Open user menu"]').click();
    const sheet = page.locator('dialog.sheet[aria-label="User menu"]');
    await sheet.locator('.who b').click();
    await expect(sheet).toBeVisible();
  });

  test('Profile link → /profile and closes the sheet', async ({ page }) => {
    await page.locator('button[aria-label="Open user menu"]').click();
    await page.locator('dialog.sheet[aria-label="User menu"] .sheet-link', { hasText: 'Profile' }).click();
    await expectView(page, /\/profile$/, 'app-profile');
    await expect(page.locator('dialog.sheet[aria-label="User menu"]')).toBeHidden();
  });

  test('Help link → /help', async ({ page }) => {
    await page.locator('button[aria-label="Open user menu"]').click();
    await page.locator('dialog.sheet[aria-label="User menu"] .sheet-link', { hasText: 'Help' }).click();
    await expectView(page, /\/help$/, 'app-help');
    await expect(page.locator('dialog.sheet[aria-label="User menu"]')).toBeHidden();
  });

  test('Report a Problem closes the sheet and opens the problem-report dialog', async ({ page }) => {
    await page.locator('button[aria-label="Open user menu"]').click();
    await page.locator('dialog.sheet[aria-label="User menu"] .sheet-link', { hasText: 'Report a Problem' }).click();
    await expect(page.locator('dialog.sheet[aria-label="User menu"]')).toBeHidden();
    // Dialog renders inline in the shell template and gates on a
    // signal; its h3 is the most stable marker independent of styling.
    await expect(page.locator('app-report-problem-dialog h3', { hasText: 'Report a Problem' }))
      .toBeVisible({ timeout: 2_000 });
  });

  test('Sign Out fires POST /auth/logout', async ({ page }) => {
    // The post-logout navigation lands at /login briefly, but the
    // login page calls auth.tryRefresh() on mount and our loginAs()
    // stub still says authenticated → it bounces back to /. The
    // logout POST is the durable, observable signal that the link
    // did its job.
    const loggedOut = page.waitForRequest(req =>
      req.method() === 'POST' && req.url().endsWith('/api/v2/auth/logout'),
    );
    await page.locator('button[aria-label="Open user menu"]').click();
    await page.locator('dialog.sheet[aria-label="User menu"] .sheet-link', { hasText: 'Sign Out' }).click();
    await loggedOut;
  });

  test('below 1024px the pill folds to the account icon', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const pill = page.locator('button[aria-label="Open user menu"]');
    await expect(pill.locator('.acct-icon')).toBeVisible();
    await expect(pill.locator('.label')).toBeHidden();
    await pill.click();
    await expect(page.locator('dialog.sheet[aria-label="User menu"]')).toBeVisible();
  });
});
