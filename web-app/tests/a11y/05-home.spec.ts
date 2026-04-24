import { test } from '@playwright/test';
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
});
