import { Component, inject, signal, OnInit, OnDestroy, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import {
  AdvancedTrackSearchFilters,
  CatalogService,
  SearchResult,
  TrackSearchHit,
} from '../../core/catalog.service';
import { PlaybackQueueService, QueuedTrack } from '../../core/playback-queue.service';
import { AppRoutes } from '../../core/routes';
import { AdvancedSearchDialogComponent } from './advanced-search-dialog';
import { PosterCardComponent } from '../../shared/media';

@Component({
  selector: 'app-search',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatButtonModule, MatProgressSpinnerModule, PosterCardComponent],
  templateUrl: './search.html',
  styleUrl: './search.scss',
})
export class SearchComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly catalog = inject(CatalogService);
  private readonly dialog = inject(MatDialog);
  private readonly queue = inject(PlaybackQueueService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly query = signal('');
  readonly results = signal<SearchResult[]>([]);

  // Advanced-search state. When any of these URL params are present,
  // we render the track-list view instead of the cross-type result grid.
  readonly trackResults = signal<TrackSearchHit[]>([]);
  readonly advancedActive = signal(false);
  readonly activeFilters = signal<AdvancedTrackSearchFilters | null>(null);

  private querySub?: Subscription;
  // Typing in the page search box updates ?q= after this pause, the
  // same way the header box waits before it fetches suggestions.
  private queryTimer: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    this.querySub = this.route.queryParamMap.subscribe(params => {
      const q = params.get('q') ?? '';
      const bpmMinRaw = params.get('bpm_min');
      const bpmMaxRaw = params.get('bpm_max');
      const ts = params.get('ts') ?? '';
      const wantsDialog = params.get('advanced') === '1';
      this.query.set(q);

      // Shell's "tune" button lands here with ?advanced=1 (+ optional
      // q=). Open the dialog immediately so the user doesn't have to
      // click a second time to reach it.
      if (wantsDialog) {
        this.openAdvanced();
        // Strip the ?advanced=1 flag so reloads don't re-open the
        // dialog and the URL stays clean. Preserve q if it was set.
        const cleaned: Record<string, string> = {};
        if (q) cleaned['q'] = q;
        this.router.navigate([this.routes.search()], { queryParams: cleaned, replaceUrl: true });
        // Falls through to the normal branches below — we may still
        // want to render a results view or empty state while the
        // dialog is open on top.
      }

      const hasAdvanced = !!(bpmMinRaw || bpmMaxRaw || ts);
      if (hasAdvanced) {
        const filters: AdvancedTrackSearchFilters = {
          query: q.trim() || undefined,
          bpmMin: bpmMinRaw ? parseInt(bpmMinRaw, 10) : undefined,
          bpmMax: bpmMaxRaw ? parseInt(bpmMaxRaw, 10) : undefined,
          timeSignature: ts || undefined,
        };
        this.activeFilters.set(filters);
        this.advancedActive.set(true);
        this.doAdvancedSearch(filters);
      } else if (q.trim().length >= 2) {
        this.advancedActive.set(false);
        this.activeFilters.set(null);
        this.trackResults.set([]);
        this.doSearch(q);
      } else {
        this.advancedActive.set(false);
        this.activeFilters.set(null);
        this.loading.set(false);
        this.results.set([]);
        this.trackResults.set([]);
      }
    });
  }

  ngOnDestroy(): void {
    this.querySub?.unsubscribe();
    this.cancelQueryTimer();
  }

  onQueryInput(value: string): void {
    this.query.set(value);
    this.cancelQueryTimer();
    this.queryTimer = setTimeout(() => this.navigateToQuery(value, true), 300);
  }

  submitQuery(): void {
    this.cancelQueryTimer();
    this.navigateToQuery(this.query(), false);
  }

  clearQuery(): void {
    this.cancelQueryTimer();
    this.query.set('');
    this.navigateToQuery('', true);
  }

  private cancelQueryTimer(): void {
    if (this.queryTimer) {
      clearTimeout(this.queryTimer);
      this.queryTimer = null;
    }
  }

  private navigateToQuery(value: string, replaceUrl: boolean): void {
    // Untrimmed, so a pause after a space does not eat it from the box.
    const q = value.trim() ? value : '';
    this.router.navigate([this.routes.search()], {
      queryParams: q ? { q } : {},
      replaceUrl,
    });
  }

  async doSearch(q: string): Promise<void> {
    this.loading.set(true);
    try {
      const data = await this.catalog.search(q.trim(), 100);
      this.results.set(data.results);
    } catch {
      this.results.set([]);
    } finally {
      this.loading.set(false);
    }
  }

  async doAdvancedSearch(filters: AdvancedTrackSearchFilters): Promise<void> {
    this.loading.set(true);
    try {
      this.trackResults.set(await this.catalog.searchTracks(filters));
    } catch {
      this.trackResults.set([]);
    } finally {
      this.loading.set(false);
    }
  }

  openAdvanced(): void {
    const ref = this.dialog.open(AdvancedSearchDialogComponent, {
      autoFocus: 'first-heading',
      // Pre-populate the dialog's text field with whatever the user
      // had typed before hitting "advanced" — the shell's tune button
      // carries the query through, and the dialog preserves it so a
      // throwaway search term isn't lost.
      data: {
        initialQuery: this.query(),
        initialBpmMin: this.activeFilters()?.bpmMin ?? null,
        initialBpmMax: this.activeFilters()?.bpmMax ?? null,
        initialTimeSignature: this.activeFilters()?.timeSignature ?? null,
      },
    });
    ref.afterClosed().subscribe(filters => {
      if (!filters) return;
      // Reflect filters in the URL so the result list is shareable /
      // bookmarkable, then let ngOnInit's queryParamMap subscriber
      // drive the actual search.
      const queryParams: Record<string, string> = {};
      if (filters.query) queryParams['q'] = filters.query;
      if (filters.bpmMin != null) queryParams['bpm_min'] = String(filters.bpmMin);
      if (filters.bpmMax != null) queryParams['bpm_max'] = String(filters.bpmMax);
      if (filters.timeSignature) queryParams['ts'] = filters.timeSignature;
      this.router.navigate([this.routes.search()], { queryParams });
    });
  }

  clearAdvanced(): void {
    this.router.navigate([this.routes.search()]);
  }

  /** Play all playable tracks from the current advanced-search result. */
  playAllTracks(): void {
    const hits = this.trackResults().filter(t => t.playable);
    if (hits.length === 0) return;
    const queued: QueuedTrack[] = hits.map(h => ({
      trackId: h.track_id,
      trackName: h.name,
      durationSeconds: h.duration_seconds,
      albumTitleId: h.title_id,
      albumName: h.album_name,
      albumPosterUrl: h.poster_url,
      primaryArtistName: h.artist_name,
    }));
    this.queue.playTracks(queued, 0);
  }

  playTrack(h: TrackSearchHit): void {
    const hits = this.trackResults().filter(t => t.playable);
    const start = hits.findIndex(t => t.track_id === h.track_id);
    if (start < 0) return;
    const queued: QueuedTrack[] = hits.map(x => ({
      trackId: x.track_id,
      trackName: x.name,
      durationSeconds: x.duration_seconds,
      albumTitleId: x.title_id,
      albumName: x.album_name,
      albumPosterUrl: x.poster_url,
      primaryArtistName: x.artist_name,
    }));
    this.queue.playTracks(queued, start);
  }

  formatTrackDuration(seconds: number | null): string {
    if (seconds == null || seconds < 1) return '';
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${s.toString().padStart(2, '0')}`;
  }

  filterSummary(): string {
    const f = this.activeFilters();
    if (!f) return '';
    const parts: string[] = [];
    if (f.query) parts.push(`"${f.query}"`);
    if (f.bpmMin != null || f.bpmMax != null) {
      const lo = f.bpmMin ?? '-';
      const hi = f.bpmMax ?? '-';
      parts.push(`${lo}-${hi} BPM`);
    }
    if (f.timeSignature) parts.push(f.timeSignature);
    return parts.join(' · ');
  }

  resultRoute(item: SearchResult): string {
    switch (item.type) {
      case 'movie': case 'tv': case 'personal':
      case 'book': case 'album':
      case 'track':
        return `/title/${item.title_id}`;
      case 'actor': return `/actor/${item.person_id}`;
      case 'artist': return `/artist/${item.artist_id}`;
      case 'author': return `/author/${item.author_id}`;
      case 'collection': return `/content/collection/${item.collection_id}`;
      case 'tag': return `/tag/${item.tag_id}`;
      case 'channel': return `/live-tv/${item.channel_id}`;
      case 'camera': return '/cameras';
      default: return '/';
    }
  }

  /** Material icon for the placeholder art of a result with no image. */
  placeholderIcon(type: SearchResult['type']): string {
    switch (type) {
      case 'actor': case 'artist': case 'author': return 'person';
      case 'book': return 'menu_book';
      case 'album': return 'album';
      case 'track': return 'music_note';
      case 'tv': return 'tv';
      case 'personal': return 'family_restroom';
      case 'collection': return 'collections_bookmark';
      case 'tag': return 'label';
      case 'channel': return 'live_tv';
      case 'camera': return 'videocam';
      default: return 'movie';
    }
  }

  /** Year, affiliation and title count, joined into one caption line. */
  detailLine(item: SearchResult): string | null {
    const parts: string[] = [];
    if (item.year) parts.push(String(item.year));
    if (item.affiliation) parts.push(item.affiliation);
    if (item.title_count) parts.push(`${item.title_count} titles`);
    return parts.length ? parts.join(' · ') : null;
  }

  typeLabel(type: string): string {
    switch (type) {
      case 'movie': return 'Movie';
      case 'tv': return 'TV Show';
      case 'personal': return 'Family Video';
      case 'book': return 'Book';
      case 'album': return 'Album';
      case 'track': return 'Track';
      case 'actor': return 'Actor';
      case 'artist': return 'Artist';
      case 'author': return 'Author';
      case 'collection': return 'Collection';
      case 'tag': return 'Tag';
      case 'channel': return 'TV Channel';
      case 'camera': return 'Camera';
      default: return type;
    }
  }
}
