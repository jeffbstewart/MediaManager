import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { fromBinary } from '@bufbuild/protobuf';
import {
  FamilyVideoSort,
  ListFamilyVideosRequestSchema,
} from '../../src/app/proto-gen/catalog_pb';

// Family / personal-videos page tests.
//
// Endpoint: CatalogService.ListFamilyVideos. Body:
//   sort: FamilyVideoSort enum (default DATE_DESC)
//   members: repeated int64 (empty list = no filter)
//   playable_only: bool (default off, opposite of books / movies)
//
// Default fixture (mock-backend's ListFamilyVideos handler):
// 2 videos — Beach Trip 2024 (2 members, 1 tag, playable, local_image_id
// set) and Holiday Recital (1 member, no tag, not playable, no
// local_image_id, mid-watch progress) — and a top-level family_members
// list of {Alex, Jamie}.

const CS = '/mediamanager.CatalogService';

interface CapturedReq { sort: FamilyVideoSort; members: bigint[]; playableOnly: boolean }

/** Decode a captured ListFamilyVideos request body. */
function decodeListFamilyVideos(req: import('@playwright/test').Request): CapturedReq {
  const decoded = fromBinary(
    ListFamilyVideosRequestSchema,
    unframeGrpcWebRequest(req.postDataBuffer()),
  );
  return {
    sort: decoded.sort,
    members: decoded.members,
    playableOnly: decoded.playableOnly,
  };
}

function captureFamilyVideoRequests(page: import('@playwright/test').Page) {
  const reqs: CapturedReq[] = [];
  page.on('request', req => {
    if (req.url().endsWith(`${CS}/ListFamilyVideos`)) {
      reqs.push(decodeListFamilyVideos(req));
    }
  });
  return () => reqs;
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

  test('hero image renders for cards with a local_image_id', async ({ page }) => {
    const heroes = page.locator('app-personal-videos .video-card img.hero-img');
    await expect(heroes).toHaveCount(1);  // only Beach Trip has one
    // SPA's videoPosterUrl() helper builds /local-images/{uuid} when
    // the proto sets local_image_id; fixture seeds "700".
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

  test('Playable chip toggle sets playable_only=true on the request', async ({ page }) => {
    const reqs = captureFamilyVideoRequests(page);
    expect(reqs().at(-1)?.playableOnly ?? false).toBe(false);

    const reqPromise = page.waitForRequest(req =>
      req.url().endsWith(`${CS}/ListFamilyVideos`),
      { timeout: 3_000 },
    );
    await page.locator('app-personal-videos mat-chip', { hasText: 'Playable' }).click();
    await reqPromise;
    expect(reqs().at(-1)?.playableOnly).toBe(true);
  });

  test('Member chip toggle sets members=[id] on the request', async ({ page }) => {
    const reqs = captureFamilyVideoRequests(page);
    expect(reqs().at(-1)?.members ?? []).toEqual([]);

    const reqPromise = page.waitForRequest(req =>
      req.url().endsWith(`${CS}/ListFamilyVideos`),
      { timeout: 3_000 },
    );
    await page.locator('app-personal-videos mat-chip', { hasText: 'Alex' }).click();
    await reqPromise;
    expect(reqs().at(-1)?.members).toEqual([1n]);
  });

  test('All People chip clears the member filter', async ({ page }) => {
    let alexReq = page.waitForRequest(req =>
      req.url().endsWith(`${CS}/ListFamilyVideos`),
      { timeout: 3_000 },
    );
    await page.locator('app-personal-videos mat-chip', { hasText: 'Alex' }).click();
    await alexReq;

    const cleared = page.waitForRequest(req =>
      req.url().endsWith(`${CS}/ListFamilyVideos`),
      { timeout: 3_000 },
    );
    await page.locator('app-personal-videos mat-chip', { hasText: 'All People' }).click();
    const got = await cleared;
    expect(decodeListFamilyVideos(got).members).toEqual([]);
  });

  test('Sort chips fire requests with the right sort enum value', async ({ page }) => {
    for (const { label, sort } of [
      { label: 'Oldest', sort: FamilyVideoSort.DATE_ASC },
      { label: 'Name',   sort: FamilyVideoSort.NAME },
      { label: 'Recent', sort: FamilyVideoSort.RECENT },
      { label: 'Newest', sort: FamilyVideoSort.DATE_DESC },
    ]) {
      const reqPromise = page.waitForRequest(req =>
        req.url().endsWith(`${CS}/ListFamilyVideos`),
        { timeout: 3_000 },
      );
      await page.locator('app-personal-videos mat-chip', { hasText: label }).click();
      const got = await reqPromise;
      expect(decodeListFamilyVideos(got).sort).toBe(sort);
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
