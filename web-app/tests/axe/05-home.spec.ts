import { test } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { auditA11y } from '../helpers/run-axe';

test.describe('/ (home)', () => {
  test('populated feed — no axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'viewer', homeFeed: 'populated' });
    await stubImages(page);
    await loginAs(page);
    await page.goto('/');
    // Home renders progressively. Wait for at least one carousel to
    // paint so we're not auditing a loading spinner.
    await page.waitForSelector('app-home', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('empty feed — no axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'viewer', homeFeed: 'empty' });
    await stubImages(page);
    await loginAs(page);
    await page.goto('/');
    await page.waitForSelector('app-home', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('admin viewing populated feed — no axe violations', async ({ page }) => {
    // Admin sees additional nav entries + badge counts in the shell.
    await mockBackend(page, { features: 'admin', homeFeed: 'populated' });
    await stubImages(page);
    await loginAs(page);
    await page.goto('/');
    await page.waitForSelector('app-home', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await auditA11y(page);
  });

  test('user sheet open — no axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'viewer', homeFeed: 'populated' });
    await stubImages(page);
    await loginAs(page);
    await page.goto('/');
    await page.waitForSelector('app-home', { state: 'attached' });
    // The pill shows the username once the profile has loaded.
    await page.waitForSelector('button.account-btn.has-account');
    await page.locator('button[aria-label="Open user menu"]').click();
    const sheet = page.locator('dialog.sheet[open]');
    await sheet.waitFor();
    // Let the slide-in finish so axe samples final colors, not a fade.
    await sheet.evaluate(d => Promise.all(d.getAnimations().map(a => a.finished)));
    await auditA11y(page, { include: 'dialog.sheet' });
  });

  test('nav sheet open at phone width — no axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin', homeFeed: 'populated' });
    await stubImages(page);
    await loginAs(page);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/');
    await page.waitForSelector('app-home', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await page.locator('button[aria-label="Open navigation"]').click();
    const sheet = page.locator('dialog.sheet.left[open]');
    await sheet.waitFor();
    await sheet.evaluate(d => Promise.all(d.getAnimations().map(a => a.finished)));
    await auditA11y(page, { include: 'dialog.sheet.left' });
  });

  test('tab bar at phone width — no axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'viewer', homeFeed: 'populated' });
    await stubImages(page);
    await loginAs(page);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/');
    await page.waitForSelector('app-home', { state: 'attached' });
    await page.waitForLoadState('networkidle');
    await page.locator('nav.tabbar a[aria-current="page"]').waitFor();
    await auditA11y(page, { include: 'nav.tabbar' });
  });
});
