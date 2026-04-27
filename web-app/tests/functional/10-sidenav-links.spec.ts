import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto } from '../helpers/proto-fixture';
import { clone } from '@bufbuild/protobuf';
import {
  FeaturesSchema,
  HomeFeedResponseSchema,
  type Features,
} from '../../src/app/proto-gen/catalog_pb';
import { featuresViewer, homeFeedPopulated } from '../fixtures-typed/home-feed.fixture';

// Sidenav navigation test. The shell exposes ~30 routes through the
// left drawer; this spec verifies each link's href matches the route
// it claims to point at, and that admin-only entries are absent for
// viewer accounts.
//
// Strategy: assert on the `<a>` element's resolved href rather than
// clicking each one. The contract is the routerLink target; clicking
// 30+ times would just exercise Angular Router with no extra signal.
// Each test does one smoke-test click at the end of its group.

interface LinkExpectation {
  /** Visible label text (inside matListItemTitle). Used to find the row. */
  label: string;
  /** RegExp matched against the resolved href. */
  hrefPattern: RegExp;
}

// Always-visible viewer entries (Home + everything alphabetically
// after, including the feature-gated rows when the gate is on).
// features.viewer.json enables every feature flag, so all conditional
// rows render here.
const VIEWER_LINKS: LinkExpectation[] = [
  { label: 'Home',         hrefPattern: /\/$/ },
  { label: 'Books',        hrefPattern: /\/content\/books$/ },
  { label: 'Cameras',      hrefPattern: /\/cameras$/ },
  { label: 'Collections',  hrefPattern: /\/content\/collections$/ },
  { label: 'Discover',     hrefPattern: /\/discover$/ },
  { label: 'Family',       hrefPattern: /\/content\/family$/ },
  { label: 'Live TV',      hrefPattern: /\/live-tv$/ },
  { label: 'Movies',       hrefPattern: /\/content\/movies$/ },
  { label: 'Music',        hrefPattern: /\/content\/music$/ },
  { label: 'Playlists',    hrefPattern: /\/content\/playlists$/ },
  { label: 'My Wish List', hrefPattern: /\/wishlist$/ },
  { label: 'Tags',         hrefPattern: /\/content\/tags$/ },
  { label: 'TV Shows',     hrefPattern: /\/content\/tv$/ },
];

// Admin-section top-level entries (i.e. NOT inside the Purchases or
// Transcodes collapsible groups). Order matches the template top to
// bottom but the test doesn't depend on order.
const ADMIN_TOP_LINKS: LinkExpectation[] = [
  { label: 'Add Item',          hrefPattern: /\/admin\/add$/ },
  { label: 'Cameras',           hrefPattern: /\/admin\/cameras$/ },
  { label: 'Data Quality',      hrefPattern: /\/admin\/data-quality$/ },
  { label: 'Family Members',    hrefPattern: /\/admin\/family-members$/ },
  { label: 'Reports',           hrefPattern: /\/admin\/reports$/ },
  { label: 'Live TV',           hrefPattern: /\/admin\/live-tv$/ },
  { label: 'Settings',          hrefPattern: /\/admin\/settings$/ },
  { label: 'Tags',              hrefPattern: /\/admin\/tags$/ },
  { label: 'Unmatched Books',   hrefPattern: /\/admin\/books\/unmatched$/ },
  { label: 'Unmatched Audio',   hrefPattern: /\/admin\/music\/unmatched$/ },
  { label: 'Users',             hrefPattern: /\/admin\/users$/ },
];

const PURCHASES_NESTED: LinkExpectation[] = [
  { label: 'Amazon Order Import', hrefPattern: /\/admin\/import$/ },
  { label: 'Document Ownership',  hrefPattern: /\/admin\/document-ownership$/ },
  { label: 'Expand',              hrefPattern: /\/admin\/expand$/ },
  { label: 'Report',              hrefPattern: /\/admin\/inventory$/ },
  { label: 'User Wishes',         hrefPattern: /\/admin\/purchase-wishes$/ },
  { label: 'Valuation',           hrefPattern: /\/admin\/valuation$/ },
];

const TRANSCODES_NESTED: LinkExpectation[] = [
  { label: 'Backlog',   hrefPattern: /\/admin\/transcodes\/backlog$/ },
  { label: 'Linked',    hrefPattern: /\/admin\/transcodes\/linked$/ },
  { label: 'Status',    hrefPattern: /\/admin\/transcodes\/status$/ },
  { label: 'Unmatched', hrefPattern: /\/admin\/transcodes\/unmatched$/ },
];

/**
 * Within the sidenav (and excluding the collapsible group children),
 * find a top-level row by its title text. Scoping to the sidenav
 * keeps "Cameras" / "Live TV" / "Tags" — which appear in BOTH the
 * non-admin and admin sections — tied to the section we want.
 */
function topLevelLink(page: Page, label: string, options: { admin?: boolean } = {}) {
  // Top-level rows are direct .mat-mdc-list-item children of the nav
  // list; nested rows have the .nested-item class. Filter that out.
  const candidates = page.locator('mat-sidenav .mat-mdc-list-item:not(.nested-item)', { hasText: label });
  // For Cameras/Live TV/Tags which collide between the non-admin and
  // admin sections, pick by the surrounding admin-section position
  // — the admin entries come AFTER the MANAGE divider, the non-admin
  // ones come BEFORE. We scope by occurrence index.
  if (options.admin) {
    return candidates.last();
  }
  return candidates.first();
}

function nestedLink(page: Page, label: string) {
  return page.locator('mat-sidenav .mat-mdc-list-item.nested-item', { hasText: label });
}

/**
 * Override BOTH gRPC RPCs that publish to FeatureService — the shell's
 * GetFeatures call AND the home component's `data.features` re-publish
 * (which arrives via HomeFeed) — so the sidenav sees a stable feature
 * set throughout the page lifecycle.
 *
 * Pass partial overrides keyed by the legacy snake_case flag name
 * (has_books, has_cameras, …); the helper translates to proto camelCase
 * and merges over the viewer base.
 */
async function setFeatures(page: Page, overrides: Record<string, boolean>) {
  const features = clone(FeaturesSchema, featuresViewer);
  const map: Record<string, keyof Features> = {
    has_books: 'hasBooks',
    has_cameras: 'hasCameras',
    has_music_radio: 'hasMusicRadio',
    has_personal_videos: 'hasPersonalVideos',
    has_live_tv: 'hasLiveTv',
    has_music: 'hasMusic',
  };
  for (const [k, v] of Object.entries(overrides)) {
    const protoKey = map[k];
    if (protoKey) (features as unknown as Record<string, boolean>)[protoKey] = v;
  }
  const home = clone(HomeFeedResponseSchema, homeFeedPopulated);
  home.features = clone(FeaturesSchema, features);
  await page.route('**/mediamanager.CatalogService/GetFeatures', r =>
    fulfillProto(r, FeaturesSchema, features));
  await page.route('**/mediamanager.CatalogService/HomeFeed', r =>
    fulfillProto(r, HomeFeedResponseSchema, home));
}

// Each row: a single feature flag, the labels that should disappear
// when it's off, and (optionally) labels that must STILL appear so
// we don't accidentally pass when the whole sidenav crashes. Music
// is a 2-for-1: the same flag gates both the Music and Playlists
// rows. The non-feature-gated rows in NEVER_GATED act as the
// regression baseline for every gate test.
const FEATURE_GATES: { flag: string; gatedLabels: string[] }[] = [
  { flag: 'has_books',           gatedLabels: ['Books'] },
  { flag: 'has_cameras',         gatedLabels: ['Cameras'] },
  { flag: 'has_music_radio',     gatedLabels: ['Discover'] },
  { flag: 'has_personal_videos', gatedLabels: ['Family'] },
  { flag: 'has_live_tv',         gatedLabels: ['Live TV'] },
  { flag: 'has_music',           gatedLabels: ['Music', 'Playlists'] },
];

const NEVER_GATED = ['Home', 'Collections', 'Movies', 'My Wish List', 'Tags', 'TV Shows'];

test.describe('sidenav — viewer (non-admin)', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await page.waitForSelector('mat-sidenav .mat-mdc-list-item');
  });

  test('all viewer links are present with the right hrefs', async ({ page }) => {
    for (const { label, hrefPattern } of VIEWER_LINKS) {
      const link = topLevelLink(page, label);
      await expect(link, `viewer link "${label}" should be visible`).toBeVisible();
      await expect(link, `"${label}" should link to ${hrefPattern}`).toHaveAttribute('href', hrefPattern);
    }
  });

  test('admin section header is absent', async ({ page }) => {
    await expect(page.locator('mat-sidenav .section-header', { hasText: 'MANAGE' })).toHaveCount(0);
  });

  test('every admin top-level link is absent', async ({ page }) => {
    for (const { hrefPattern } of ADMIN_TOP_LINKS) {
      await expect(
        page.locator('mat-sidenav a.mat-mdc-list-item').filter({ has: page.locator(`a[href*="/admin/"]`) }),
      ).toHaveCount(0, { timeout: 1_000 });
      // Belt-and-braces: assert no link with the admin href exists at all.
      await expect(page.locator(`mat-sidenav a[href$="${hrefPattern.source.replace(/\\\//g, '/').replace(/\$$/, '')}"]`)).toHaveCount(0);
    }
  });

  test('clicking Movies actually navigates', async ({ page }) => {
    await topLevelLink(page, 'Movies').click();
    await expect(page).toHaveURL(/\/content\/movies$/);
  });
});

test.describe('sidenav — admin', () => {
  test.beforeEach(async ({ page }) => {
    // mockBackend's gRPC HomeFeed dispatch automatically pairs the
    // home fixture's nested `features` with the admin features
    // payload when opts.features === 'admin', so the home page's
    // onload-republish keeps the admin flags set rather than
    // overwriting them with the viewer default.
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await page.waitForSelector('mat-sidenav .section-header');
  });

  test('admin section header is visible', async ({ page }) => {
    await expect(page.locator('mat-sidenav .section-header', { hasText: 'MANAGE' })).toBeVisible();
  });

  test('every admin top-level link has the right href', async ({ page }) => {
    for (const { label, hrefPattern } of ADMIN_TOP_LINKS) {
      const link = topLevelLink(page, label, { admin: true });
      await expect(link, `admin link "${label}" should be visible`).toBeVisible();
      await expect(link, `admin "${label}" should link to ${hrefPattern}`).toHaveAttribute('href', hrefPattern);
    }
  });

  test('Purchases group expands and exposes its nested links', async ({ page }) => {
    // Group is collapsed by default — click the toggle button.
    await page.locator('mat-sidenav button.mat-mdc-list-item', { hasText: 'Purchases' }).click();
    for (const { label, hrefPattern } of PURCHASES_NESTED) {
      const link = nestedLink(page, label);
      await expect(link, `Purchases > "${label}" should be visible after expand`).toBeVisible();
      await expect(link).toHaveAttribute('href', hrefPattern);
    }
  });

  test('Transcodes group expands and exposes its nested links', async ({ page }) => {
    await page.locator('mat-sidenav button.mat-mdc-list-item', { hasText: 'Transcodes' }).click();
    for (const { label, hrefPattern } of TRANSCODES_NESTED) {
      const link = nestedLink(page, label);
      await expect(link, `Transcodes > "${label}" should be visible after expand`).toBeVisible();
      await expect(link).toHaveAttribute('href', hrefPattern);
    }
  });

  test('clicking an admin link actually navigates', async ({ page }) => {
    await topLevelLink(page, 'Settings', { admin: true }).click();
    await expect(page).toHaveURL(/\/admin\/settings$/);
  });

  test('clicking a nested admin link actually navigates', async ({ page }) => {
    await page.locator('mat-sidenav button.mat-mdc-list-item', { hasText: 'Transcodes' }).click();
    await nestedLink(page, 'Backlog').click();
    await expect(page).toHaveURL(/\/admin\/transcodes\/backlog$/);
  });
});

test.describe('sidenav — feature flag gates', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page, { features: 'viewer' });
    await loginAs(page);
    await stubImages(page);
  });

  // One test per flag: turn that flag OFF (every other flag stays on),
  // assert the gated label(s) vanish, and assert the always-visible
  // baseline labels stay so we know the sidenav still rendered.
  for (const { flag, gatedLabels } of FEATURE_GATES) {
    test(`${flag}=false hides ${gatedLabels.join(' + ')}`, async ({ page }) => {
      await setFeatures(page, { [flag]: false });
      await page.goto('/');
      await page.waitForSelector('mat-sidenav .mat-mdc-list-item');

      for (const label of gatedLabels) {
        await expect(
          topLevelLink(page, label),
          `"${label}" should be hidden when ${flag}=false`,
        ).toHaveCount(0);
      }
      // Baseline: untouched rows still render. Catches the "I broke
      // the sidenav entirely" failure mode where everything is missing.
      for (const label of NEVER_GATED) {
        await expect(
          topLevelLink(page, label),
          `"${label}" should remain visible regardless of feature flags`,
        ).toBeVisible();
      }
    });
  }

  test('all feature flags off → only the always-visible rows remain', async ({ page }) => {
    const allOff = Object.fromEntries(
      FEATURE_GATES.map(g => [g.flag, false]),
    );
    await setFeatures(page, allOff);
    await page.goto('/');
    await page.waitForSelector('mat-sidenav .mat-mdc-list-item');

    // Every gated label is gone.
    for (const { gatedLabels } of FEATURE_GATES) {
      for (const label of gatedLabels) {
        await expect(topLevelLink(page, label)).toHaveCount(0);
      }
    }
    // Baseline still present.
    for (const label of NEVER_GATED) {
      await expect(topLevelLink(page, label)).toBeVisible();
    }
    // Total list-item count equals the baseline length — no extras.
    await expect(page.locator('mat-sidenav .mat-mdc-list-item:not(.nested-item)'))
      .toHaveCount(NEVER_GATED.length);
  });
});
