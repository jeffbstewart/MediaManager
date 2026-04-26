import { test, expect, Page } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// /admin/family-members — FamilyMembersComponent. Mat-table of
// members + Edit/Delete actions + a New Member dialog.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await page.route('**/api/v2/admin/family-members', r => {
    if (r.request().method() === 'POST') return r.fulfill({ json: { id: 99 } });
    return r.fallback();
  });
  await page.route('**/api/v2/admin/family-members/*', r => {
    const m = r.request().method();
    if (m === 'POST') return r.fulfill({ json: { ok: true } });
    if (m === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.goto('/admin/family-members');
  await page.waitForSelector('app-family-members table');
}

test.describe('admin family-members — display', () => {
  test('renders the 3 fixture rows with name + video count + initial', async ({ page }) => {
    await setup(page);
    const rows = page.locator('app-family-members tbody tr');
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0).locator('.member-name')).toContainText('Alice Stewart');
    await expect(rows.nth(0).locator('.initial-circle')).toContainText('A');
    await expect(rows.nth(0)).toContainText('24');
  });

  test('notes column renders or stays blank', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-family-members tr', { hasText: 'Alice' }).locator('.notes-text'))
      .toContainText('Eldest daughter');
  });
});

test.describe('admin family-members — Create dialog', () => {
  test('opens via New Member, Save disabled until name entered', async ({ page }) => {
    await setup(page);
    await page.locator('app-family-members button', { hasText: 'New Member' }).click();
    await expect(page.locator('app-family-members .modal-overlay h3')).toContainText('New Family Member');
    const save = page.locator('app-family-members .modal-overlay button', { hasText: 'Save' });
    await expect(save).toBeDisabled();
    await page.locator('app-family-members #family-member-name').fill('Dana');
    await expect(save).toBeEnabled();
  });

  test('save POSTs to /family-members with name + notes', async ({ page }) => {
    await setup(page);
    await page.locator('app-family-members button', { hasText: 'New Member' }).click();
    await page.locator('app-family-members #family-member-name').fill('Dana');
    await page.locator('app-family-members #family-member-notes').fill('Cousin');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/family-members'),
      { timeout: 3_000 },
    );
    await page.locator('app-family-members .modal-overlay button', { hasText: 'Save' }).click();
    const got = await req;
    expect(got.postDataJSON()).toEqual({ name: 'Dana', notes: 'Cousin' });
  });

  test('save with blank notes posts notes=null', async ({ page }) => {
    await setup(page);
    await page.locator('app-family-members button', { hasText: 'New Member' }).click();
    await page.locator('app-family-members #family-member-name').fill('Dana');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/family-members'),
      { timeout: 3_000 },
    );
    await page.locator('app-family-members .modal-overlay button', { hasText: 'Save' }).click();
    const got = await req;
    expect(got.postDataJSON().notes).toBeNull();
  });
});

test.describe('admin family-members — Edit dialog', () => {
  test('Edit pre-fills name + notes', async ({ page }) => {
    await setup(page);
    await page.locator('app-family-members button[aria-label="Edit"]').first().click();
    await expect(page.locator('app-family-members .modal-overlay h3')).toContainText('Edit Family Member');
    await expect(page.locator('app-family-members #family-member-name')).toHaveValue('Alice Stewart');
    await expect(page.locator('app-family-members #family-member-notes')).toHaveValue(/Eldest daughter/);
  });

  test('save POSTs to /family-members/:id', async ({ page }) => {
    await setup(page);
    await page.locator('app-family-members button[aria-label="Edit"]').first().click();
    await page.locator('app-family-members #family-member-name').fill('Alice S.');
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/family-members/1'),
      { timeout: 3_000 },
    );
    await page.locator('app-family-members .modal-overlay button', { hasText: 'Save' }).click();
    const got = await req;
    expect(got.postDataJSON().name).toBe('Alice S.');
  });
});

test.describe('admin family-members — Delete confirm', () => {
  test('confirm with non-zero video count notes the count', async ({ page }) => {
    await setup(page);
    await page.locator('app-family-members button[aria-label="Delete"]').first().click();
    await expect(page.locator('app-family-members h3', { hasText: 'Delete Family Member' })).toBeVisible();
    await expect(page.locator('app-family-members .modal-overlay p')).toContainText('appear in 24 videos');
  });

  test('confirm DELETEs /family-members/:id', async ({ page }) => {
    await setup(page);
    await page.locator('app-family-members button[aria-label="Delete"]').first().click();
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/admin/family-members/1'),
      { timeout: 3_000 },
    );
    await page.locator('app-family-members .modal-overlay button', { hasText: 'Delete' }).click();
    await req;
  });
});
