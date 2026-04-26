import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// Functional spec for /help (HelpComponent). The page is static —
// no API calls, no signals — so the tests just verify structural
// rendering: heading hierarchy, table-of-contents anchors, the
// keyboard-shortcuts table, and that internal anchors point to
// real section ids.

test.describe('help page', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await page.goto('/help');
    await page.waitForSelector('app-help h1');
  });

  test('renders the page title + intro', async ({ page }) => {
    await expect(page.locator('app-help h1')).toContainText('Media Manager Help');
    await expect(page.locator('app-help .intro'))
      .toContainText('streaming hub');
  });

  test('table of contents lists all eight sections', async ({ page }) => {
    const tocLinks = page.locator('app-help nav.toc ul li a');
    await expect(tocLinks).toHaveCount(8);
    await expect(tocLinks.nth(0)).toContainText('Home');
    await expect(tocLinks.nth(1)).toContainText('Browsing');
    await expect(tocLinks.nth(2)).toContainText('Watching');
    await expect(tocLinks.nth(3)).toContainText('Wish List');
    await expect(tocLinks.nth(4)).toContainText('Cameras');
    await expect(tocLinks.nth(5)).toContainText('Live TV');
    await expect(tocLinks.nth(6)).toContainText('Profile');
    await expect(tocLinks.nth(7)).toContainText('Keyboard Shortcuts');
  });

  test('every TOC anchor target exists as a section[id]', async ({ page }) => {
    // Pull the eight expected fragment ids from the TOC, then verify
    // each one matches a <section> on the page. Catches any future
    // copy-paste drift between the TOC and the body anchors.
    const fragments = await page.locator('app-help nav.toc a').evaluateAll(
      els => els.map(a => (a as HTMLAnchorElement).getAttribute('href')?.split('#')[1])
    );
    for (const frag of fragments) {
      expect(frag).toBeTruthy();
      await expect(page.locator(`app-help section#${frag}`)).toHaveCount(1);
    }
  });

  test('section h2 headings render in document order', async ({ page }) => {
    const h2s = page.locator('app-help section > h2');
    await expect(h2s).toHaveCount(8);
    await expect(h2s.nth(0)).toContainText('Home');
    await expect(h2s.nth(7)).toContainText('Keyboard Shortcuts');
  });

  test('keyboard shortcuts table renders with the documented rows', async ({ page }) => {
    const rows = page.locator('app-help table.shortcut-table tbody tr');
    await expect(rows).toHaveCount(4);
    await expect(rows.nth(0).locator('kbd')).toContainText('Space');
    await expect(rows.nth(0)).toContainText('Play / Pause');
    await expect(rows.nth(1).locator('kbd')).toContainText('F');
    await expect(rows.nth(2).locator('kbd')).toContainText('Escape');
    // Row 4 is Double-click — no kbd element.
    await expect(rows.nth(3)).toContainText('Double-click');
  });

  test('Profile reference link points at the in-app /profile route', async ({ page }) => {
    await expect(page.locator('app-help section#livetv a[href="profile"]')).toBeVisible();
  });
});
