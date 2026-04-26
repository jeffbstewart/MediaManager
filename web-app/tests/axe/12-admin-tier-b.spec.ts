import { test } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { auditA11y } from '../helpers/run-axe';

// Tier B — transcode pipeline + unmatched media.
// These six pages share list/grid scaffolding and dense action toolbars.
// Pre-scrub already tokenized the dim-text patterns; expected residue is
// status iconography and any per-page table-header oddities.
test.describe('admin Tier B — transcode + unmatched', () => {
  test('/admin/transcodes/status renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/transcodes/status');
    await page.waitForSelector('app-transcode-status .status-page');
    await auditA11y(page);
  });

  test('/admin/transcodes/unmatched renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/transcodes/unmatched');
    await page.waitForSelector('app-transcode-unmatched table, app-transcode-unmatched .empty-state');
    await auditA11y(page);
  });

  test('/admin/transcodes/linked renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/transcodes/linked');
    await page.waitForSelector('app-transcode-linked table');
    await auditA11y(page);
  });

  test('/admin/transcodes/backlog renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/transcodes/backlog');
    await page.waitForSelector('app-transcode-backlog table, app-transcode-backlog .empty-text');
    await auditA11y(page);
  });

  test('/admin/books/unmatched renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/books/unmatched');
    await page.waitForSelector('app-unmatched-books table, app-unmatched-books .empty-state');
    await auditA11y(page);
  });

  test('/admin/music/unmatched renders with zero axe violations', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/admin/music/unmatched');
    await page.waitForSelector('app-unmatched-audio .group-list, app-unmatched-audio .empty');
    await auditA11y(page);
  });
});
