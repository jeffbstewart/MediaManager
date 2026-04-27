import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto } from '../helpers/proto-fixture';
import { create } from '@bufbuild/protobuf';
import {
  SearchResultSchema,
  SearchResultType,
} from '../../src/app/proto-gen/common_pb';
import {
  AdvancedSearchPresetsResponseSchema,
  SearchResponseSchema,
  SearchTracksResponseSchema,
  TrackSearchHitSchema,
} from '../../src/app/proto-gen/catalog_pb';

// /search results page — SearchComponent. Targets search.ts (52.8%) +
// search.html (48.5%) + advanced-search-dialog.ts (34.4%) coverage gaps.
//
// SearchComponent has two render branches:
//   - cross-type result grid (default — q=...)
//   - filtered track list (advanced — bpm_min/bpm_max/ts in URL)
// And the advanced-search dialog adds preset chips + form submission.

const CS = '/mediamanager.CatalogService';

async function setup(page: Page) {
  await mockBackend(page);
  await loginAs(page);
  await stubImages(page);
}

test.describe('search — cross-type result grid', () => {
  test('renders results count + every fixture row', async ({ page }) => {
    await setup(page);
    await page.goto('/search?q=test');
    await page.waitForSelector('app-search');
    await expect(page.locator('app-search h2')).toContainText('test');
    await expect(page.locator('.result-count')).toContainText('9 results');
    await expect(page.locator('.result-card')).toHaveCount(9);
  });

  test('result cards link to the correct per-type route', async ({ page }) => {
    await setup(page);
    await page.goto('/search?q=test');
    await page.waitForSelector('app-search .results-grid');
    // movie 100 → /title/100
    await expect(page.locator('.result-card', { hasText: 'The Matrix' }).first())
      .toHaveAttribute('href', '/title/100');
    // actor 6384 → /actor/6384
    await expect(page.locator('.result-card', { hasText: 'Keanu Reeves' }))
      .toHaveAttribute('href', '/actor/6384');
    // artist 1 → /artist/1
    await expect(page.locator('.result-card', { hasText: 'Miles Davis' }))
      .toHaveAttribute('href', '/artist/1');
    // author 1 → /author/1
    await expect(page.locator('.result-card', { hasText: 'Frank Herbert' }))
      .toHaveAttribute('href', '/author/1');
    // collection 2 → /content/collection/2
    await expect(page.locator('.result-card', { hasText: 'Matrix Collection' }))
      .toHaveAttribute('href', '/content/collection/2');
    // tag 1 → /tag/1
    await expect(page.locator('.result-card', { hasText: 'Comfort Watch' }))
      .toHaveAttribute('href', '/tag/1');
  });

  test('typeLabel renders the human label for each row type', async ({ page }) => {
    await setup(page);
    await page.goto('/search?q=test');
    await page.waitForSelector('app-search .results-grid');
    const labels = await page.locator('.result-type').allTextContents();
    expect(labels).toContain('Movie');
    expect(labels).toContain('TV Show');
    expect(labels).toContain('Album');
    expect(labels).toContain('Actor');
    expect(labels).toContain('Artist');
    expect(labels).toContain('Author');
    expect(labels).toContain('Collection');
    expect(labels).toContain('Tag');
    expect(labels).toContain('Book');
  });

  test('result with no poster + no headshot renders the type-icon placeholder', async ({ page }) => {
    await setup(page);
    await page.route(`**${CS}/Search`, r => fulfillProto(r, SearchResponseSchema, create(SearchResponseSchema, {
      query: 'x',
      results: [create(SearchResultSchema, {
        resultType: SearchResultType.CHANNEL,
        name: 'CNN',
        channelId: 99n,
      })],
    })));
    await page.goto('/search?q=cnn');
    await page.waitForSelector('app-search .results-grid');
    await expect(page.locator('.result-placeholder')).toBeVisible();
    await expect(page.locator('.result-card')).toHaveAttribute('href', '/live-tv/99');
  });

  test('camera result has the static /cameras href', async ({ page }) => {
    await setup(page);
    await page.route(`**${CS}/Search`, r => fulfillProto(r, SearchResponseSchema, create(SearchResponseSchema, {
      query: 'cam',
      results: [create(SearchResultSchema, {
        resultType: SearchResultType.CAMERA,
        name: 'Front Door',
      })],
    })));
    await page.goto('/search?q=cam');
    await page.waitForSelector('app-search .results-grid');
    await expect(page.locator('.result-card')).toHaveAttribute('href', '/cameras');
  });

  test('empty results renders the "No results found" hint', async ({ page }) => {
    await setup(page);
    await page.route(`**${CS}/Search`, r =>
      fulfillProto(r, SearchResponseSchema, create(SearchResponseSchema, { query: 'nothing' })));
    await page.goto('/search?q=nothing');
    await page.waitForSelector('app-search');
    await expect(page.locator('.empty-text')).toContainText('No results found');
  });

  test('search API failure also renders the empty hint (catch branch)', async ({ page }) => {
    await setup(page);
    await page.route(`**${CS}/Search`, r => r.fulfill({ status: 500 }));
    await page.goto('/search?q=boom');
    await page.waitForSelector('app-search');
    await expect(page.locator('.empty-text')).toContainText('No results found');
  });

  test('single-character query short-circuits without firing Search', async ({ page }) => {
    await setup(page);
    let fired = false;
    await page.route(`**${CS}/Search`, r => {
      fired = true;
      return fulfillProto(r, SearchResponseSchema, create(SearchResponseSchema));
    });
    await page.goto('/search?q=a');
    await page.waitForSelector('app-search');
    await page.waitForTimeout(200);
    expect(fired).toBe(false);
  });
});

test.describe('search — advanced track list view', () => {
  const tracksFixture = create(SearchTracksResponseSchema, {
    tracks: [
      create(TrackSearchHitSchema, {
        trackId: 1n, name: 'Stayin Alive', albumName: 'Saturday Night Fever',
        titleId: 500n, artistName: 'Bee Gees', posterUrl: '/posters/w185/500',
        bpm: 104, timeSignature: '4/4', durationSeconds: 285, playable: true,
      }),
      create(TrackSearchHitSchema, {
        trackId: 2n, name: 'Take Five', albumName: 'Time Out',
        titleId: 501n, artistName: 'Brubeck',
        bpm: 174, timeSignature: '5/4', durationSeconds: 324, playable: false,
      }),
    ],
  });
  const emptyTracks = create(SearchTracksResponseSchema, {});

  test('?bpm_min triggers advanced render: filter summary + track list', async ({ page }) => {
    await setup(page);
    await page.route(`**${CS}/SearchTracks`, r =>
      fulfillProto(r, SearchTracksResponseSchema, tracksFixture));
    await page.goto('/search?bpm_min=100&bpm_max=120');
    await page.waitForSelector('app-search');
    await expect(page.locator('h2')).toContainText('Advanced search');
    await expect(page.locator('.filter-summary')).toContainText('100-120 BPM');
    await expect(page.locator('app-search .track-row')).toHaveCount(2);
    await expect(page.locator('app-search .track-row').first()).toContainText('Stayin Alive');
    // 285s = 4:45
    await expect(page.locator('app-search .track-row .track-duration').first()).toContainText('4:45');
  });

  test('open-ended BPM range renders "100--" / "-120" summaries', async ({ page }) => {
    await setup(page);
    await page.route(`**${CS}/SearchTracks`, r =>
      fulfillProto(r, SearchTracksResponseSchema, emptyTracks));
    await page.goto('/search?bpm_min=100');
    await page.waitForSelector('app-search');
    await expect(page.locator('.filter-summary')).toContainText('100--');
  });

  test('Clear filters button navigates back to /search', async ({ page }) => {
    await setup(page);
    await page.route(`**${CS}/SearchTracks`, r =>
      fulfillProto(r, SearchTracksResponseSchema, emptyTracks));
    await page.goto('/search?bpm_min=100');
    await page.waitForSelector('app-search');
    await page.locator('button', { hasText: 'Clear filters' }).click();
    await expect(page).toHaveURL(/\/search$/);
  });

  test('empty track-results renders the "No tracks match" hint', async ({ page }) => {
    await setup(page);
    await page.route(`**${CS}/SearchTracks`, r =>
      fulfillProto(r, SearchTracksResponseSchema, emptyTracks));
    await page.goto('/search?bpm_min=400');
    await page.waitForSelector('app-search');
    await expect(page.locator('.empty-text')).toContainText('No tracks match');
  });

  test('searchTracks failure (500) renders the empty hint', async ({ page }) => {
    await setup(page);
    await page.route(`**${CS}/SearchTracks`, r => r.fulfill({ status: 500 }));
    await page.goto('/search?bpm_min=100');
    await page.waitForSelector('app-search');
    await expect(page.locator('.empty-text')).toContainText('No tracks match');
  });

  test('Play All starts the queue (playable filtered)', async ({ page }) => {
    await setup(page);
    await page.route(`**${CS}/SearchTracks`, r =>
      fulfillProto(r, SearchTracksResponseSchema, tracksFixture));
    await page.route('**/audio/*', r => r.fulfill({ status: 204 }));
    await page.goto('/search?bpm_min=100');
    await page.waitForSelector('app-search .track-row');
    await page.locator('button', { hasText: 'Play all' }).click();
    await expect(page.locator('app-audio-player .audio-player'))
      .toHaveClass(/visible/, { timeout: 2_000 });
  });

  test('per-track play button is disabled for unplayable rows', async ({ page }) => {
    await setup(page);
    await page.route(`**${CS}/SearchTracks`, r =>
      fulfillProto(r, SearchTracksResponseSchema, tracksFixture));
    await page.goto('/search?bpm_min=100');
    await page.waitForSelector('app-search .track-row');
    // Take Five is unplayable
    const unplayable = page.locator('.track-row').nth(1);
    await expect(unplayable).toHaveClass(/unplayable/);
    await expect(unplayable.locator('button[aria-label="Play this track"]')).toBeDisabled();
  });
});

test.describe('search — advanced search dialog', () => {
  test('?advanced=1 opens the dialog + strips the flag from the URL', async ({ page }) => {
    await setup(page);
    await page.goto('/search?q=jazz&advanced=1');
    await expect(page.locator('app-advanced-search-dialog')).toBeVisible({ timeout: 2_000 });
    // search.ts replaceUrl()s away the flag; q= is preserved.
    await expect(page).toHaveURL(/\/search\?q=jazz$/);
    // Dialog seeded with q=jazz — input value should reflect that.
    await expect(page.locator('app-advanced-search-dialog input[type="text"]'))
      .toHaveValue('jazz');
  });

  test('preset chip click pre-fills bpm + time-sig fields', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await page.route(`**${CS}/ListAdvancedSearchPresets`, r =>
      fulfillProto(r, AdvancedSearchPresetsResponseSchema, create(AdvancedSearchPresetsResponseSchema, {
        presets: [{
          key: 'waltz', name: 'Waltz', description: 'Slow waltz',
          bpmMin: 80, bpmMax: 100, timeSignature: '3/4',
        }],
      })));
    await page.goto('/search?advanced=1');
    await page.waitForSelector('app-advanced-search-dialog');
    await page.locator('mat-chip', { hasText: 'Waltz' }).click();
    await expect(page.locator('app-advanced-search-dialog input[placeholder="-"]').first())
      .toHaveValue('80');
    await expect(page.locator('app-advanced-search-dialog input[placeholder="-"]').nth(1))
      .toHaveValue('100');
    await expect(page.locator('app-advanced-search-dialog select')).toHaveValue('3/4');
  });

  test('Search button with values closes the dialog and updates URL', async ({ page }) => {
    await setup(page);
    await page.route(`**${CS}/SearchTracks`, r =>
      fulfillProto(r, SearchTracksResponseSchema, create(SearchTracksResponseSchema)));
    await page.goto('/search?advanced=1');
    await page.waitForSelector('app-advanced-search-dialog');
    await page.locator('app-advanced-search-dialog input[type="text"]').fill('bossa');
    await page.locator('app-advanced-search-dialog input[placeholder="-"]').first().fill('110');
    await page.locator('app-advanced-search-dialog mat-dialog-actions button.mat-mdc-unelevated-button').click();
    await expect(page.locator('app-advanced-search-dialog')).toHaveCount(0, { timeout: 2_000 });
    await expect(page).toHaveURL(/q=bossa/);
    await expect(page).toHaveURL(/bpm_min=110/);
  });

  test('Search with all-blank fields is a no-op (dialog stays open)', async ({ page }) => {
    await setup(page);
    await page.goto('/search?advanced=1');
    await page.waitForSelector('app-advanced-search-dialog');
    // Make sure all fields are empty.
    await page.locator('app-advanced-search-dialog button', { hasText: 'Clear' }).click();
    await page.locator('app-advanced-search-dialog mat-dialog-actions button.mat-mdc-unelevated-button').click();
    await page.waitForTimeout(200);
    await expect(page.locator('app-advanced-search-dialog')).toBeVisible();
  });

  test('Cancel closes the dialog without updating the URL', async ({ page }) => {
    await setup(page);
    await page.goto('/search?q=jazz&advanced=1');
    await page.waitForSelector('app-advanced-search-dialog');
    await page.locator('app-advanced-search-dialog button', { hasText: 'Cancel' }).click();
    await expect(page.locator('app-advanced-search-dialog')).toHaveCount(0, { timeout: 2_000 });
    // URL still has q=jazz (the flag was stripped on dialog open).
    await expect(page).toHaveURL(/q=jazz/);
  });

  test('Clear button resets all fields back to empty', async ({ page }) => {
    await setup(page);
    await page.goto('/search?q=foo&bpm_min=120&bpm_max=140&ts=4/4');
    await page.waitForSelector('app-search');
    // Open dialog from the page header.
    await page.locator('app-search button', { hasText: /Advanced/ }).first().click();
    await page.waitForSelector('app-advanced-search-dialog');
    await page.locator('app-advanced-search-dialog button', { hasText: 'Clear' }).click();
    await expect(page.locator('app-advanced-search-dialog input[type="text"]'))
      .toHaveValue('');
    await expect(page.locator('app-advanced-search-dialog input[placeholder="-"]').first())
      .toHaveValue('');
  });

  test('preset load failure renders "No presets available"', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await page.route(`**${CS}/ListAdvancedSearchPresets`, r => r.fulfill({ status: 500 }));
    await page.goto('/search?advanced=1');
    await page.waitForSelector('app-advanced-search-dialog');
    await expect(page.locator('app-advanced-search-dialog .muted'))
      .toContainText('No presets');
  });
});
