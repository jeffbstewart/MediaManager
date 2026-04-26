import { test, expect } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Family / personal-videos page tests.
//
// Endpoint: /api/v2/catalog/family-videos
//   - sort=<mode>            (always set; default 'date_desc')
//   - members=<id,id>        (only when non-empty)
//   - playable_only=true     (only when true; default off, opposite
//                              of the books / movies page)
//
// Fixture: catalog/family-videos.list.json — 2 videos (Beach Trip
// 2024 with 2 family members + 1 tag + playable + poster; Holiday
// Recital with 1 member, no tag, not playable, no poster, mid-watch
// progress) and a top-level family_members list of {Alex, Jamie}.

function captureFamilyVideoRequests(page: import('@playwright/test').Page) {
  const urls: string[] = [];
  page.on('request', req => {
    if (req.url().includes('/api/v2/catalog/family-videos')) urls.push(req.url());
  });
  return () => urls;
}

test.describe('personal videos page', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/content/family');
    await page.waitForSelector('app-personal-videos .video-grid');
  });

  // -------- Landing page basics --------

  test('renders both videos with title + total label', async ({ page }) => {
    const cards = page.locator('app-personal-videos .video-card');
    await expect(cards).toHaveCount(2);

    await expect(page.locator('app-personal-videos .status-label'))
      .toContainText('2 family videos');

    // Newest-first sort is the default; Beach Trip 2024 leads.
    await expect(cards.first().locator('.video-title')).toContainText('Family Beach Trip 2024');
    await expect(cards.nth(1).locator('.video-title')).toContainText('Holiday Recital');
  });

  test('hero image renders for cards with a poster_url', async ({ page }) => {
    const heroes = page.locator('app-personal-videos .video-card img.hero-img');
    await expect(heroes).toHaveCount(1);  // only Beach Trip has one
    await expect(heroes.first()).toHaveAttribute('src', /\/local-images\/700$/);
  });

  test('event date and description render when present', async ({ page }) => {
    const beach = page.locator('app-personal-videos .video-card').first();
    await expect(beach.locator('.event-date')).toBeVisible();
    await expect(beach.locator('.description')).toContainText('A week at the Outer Banks');

    // Holiday Recital has neither date renders empty body and no
    // description block — the card just shows the title.
    const holiday = page.locator('app-personal-videos .video-card').nth(1);
    await expect(holiday.locator('.description')).toHaveCount(0);
  });

  test('play icon shows on playable videos and is absent on others', async ({ page }) => {
    const cards = page.locator('app-personal-videos .video-card');
    await expect(cards.first().locator('.play-icon')).toBeVisible();
    await expect(cards.nth(1).locator('.play-icon')).toHaveCount(0);
  });

  test('family-member chips render under the right cards', async ({ page }) => {
    const beachChips = page.locator('app-personal-videos .video-card').first()
      .locator('.member-chip');
    await expect(beachChips).toHaveCount(2);
    await expect(beachChips.first()).toContainText('Alex');
    await expect(beachChips.nth(1)).toContainText('Jamie');

    const holidayChips = page.locator('app-personal-videos .video-card').nth(1)
      .locator('.member-chip');
    await expect(holidayChips).toHaveCount(1);
    await expect(holidayChips.first()).toContainText('Alex');
  });

  test('tag chips render with their inline colors', async ({ page }) => {
    const tag = page.locator('app-personal-videos .video-card').first()
      .locator('.tag-chip');
    await expect(tag).toHaveCount(1);
    await expect(tag).toContainText('Vacation');
    // Inline style binding sets background + color from the fixture.
    await expect(tag).toHaveAttribute('style', /background-color:\s*rgb\(29, 78, 216\)/);
  });

  test('progress overlay renders on a partly-watched video', async ({ page }) => {
    const holiday = page.locator('app-personal-videos .video-card').nth(1);
    await expect(holiday.locator('.progress-fill')).toHaveAttribute('style', /width:\s*60%/);
  });

  test('clicking a card navigates to /title/:id', async ({ page }) => {
    await page.locator('app-personal-videos .video-card').first().click();
    await expect(page).toHaveURL(/\/title\/700$/);
  });

  // -------- Filter chips --------

  test('Playable chip toggle adds playable_only=true to the request', async ({ page }) => {
    const urls = captureFamilyVideoRequests(page);
    // Default: playable_only is OFF (opposite of books/movies).
    expect(urls().at(-1) ?? '').not.toContain('playable_only');

    const reqPromise = page.waitForRequest(req =>
      req.url().includes('/api/v2/catalog/family-videos')
        && req.url().includes('playable_only=true'),
      { timeout: 3_000 },
    );
    await page.locator('app-personal-videos mat-chip', { hasText: 'Playable' }).click();
    await reqPromise;
    expect(urls().at(-1)).toContain('playable_only=true');
  });

  test('Member chip toggle adds members=<id> to the request', async ({ page }) => {
    const urls = captureFamilyVideoRequests(page);
    expect(urls().at(-1) ?? '').not.toContain('members');

    // Click "Alex" (id=1).
    const reqPromise = page.waitForRequest(req =>
      req.url().includes('/api/v2/catalog/family-videos')
        && /members=1\b/.test(req.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-personal-videos mat-chip', { hasText: 'Alex' }).click();
    await reqPromise;
    expect(urls().at(-1)).toMatch(/members=1\b/);
  });

  test('All People chip clears the member filter', async ({ page }) => {
    // Pick Alex first so there's something to clear. Set the wait
    // BEFORE clicking — the refresh fires synchronously inside the
    // same task as the click, so a wait started after misses it.
    let alexReq = page.waitForRequest(req =>
      req.url().includes('/api/v2/catalog/family-videos')
        && /members=1\b/.test(req.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-personal-videos mat-chip', { hasText: 'Alex' }).click();
    await alexReq;

    // Then click All People — the resulting request should drop the
    // members param entirely.
    const cleared = page.waitForRequest(req => {
      if (!req.url().includes('/api/v2/catalog/family-videos')) return false;
      return !new URL(req.url()).searchParams.has('members');
    }, { timeout: 3_000 });
    await page.locator('app-personal-videos mat-chip', { hasText: 'All People' }).click();
    await cleared;
  });

  test('Sort chips fire requests with the right sort=<mode>', async ({ page }) => {
    for (const { label, mode } of [
      { label: 'Oldest', mode: 'date_asc' },
      { label: 'Name',   mode: 'name' },
      { label: 'Recent', mode: 'recent' },
      { label: 'Newest', mode: 'date_desc' },
    ]) {
      const reqPromise = page.waitForRequest(req =>
        req.url().includes('/api/v2/catalog/family-videos')
          && req.url().includes(`sort=${mode}`),
        { timeout: 3_000 },
      );
      await page.locator('app-personal-videos mat-chip', { hasText: label }).click();
      await reqPromise;
    }
  });

  test('selected sort chip is highlighted', async ({ page }) => {
    // Default is "Newest" (date_desc).
    const newest = page.locator('app-personal-videos mat-chip', { hasText: 'Newest' });
    await expect(newest).toHaveClass(/mat-mdc-chip-highlighted/);

    await page.locator('app-personal-videos mat-chip', { hasText: 'Oldest' }).click();
    await expect(page.locator('app-personal-videos mat-chip', { hasText: 'Oldest' }))
      .toHaveClass(/mat-mdc-chip-highlighted/);
    await expect(newest).not.toHaveClass(/mat-mdc-chip-highlighted/);
  });
});
