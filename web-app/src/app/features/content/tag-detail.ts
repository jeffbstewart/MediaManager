import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { PosterCardComponent } from '../../shared/media';
import { CatalogService, TagCard, TaggedTrackCard, TitleCard } from '../../core/catalog.service';
import { FeatureService } from '../../core/feature.service';
import { PlaybackQueueService, QueuedTrack } from '../../core/playback-queue.service';
import { AppRoutes } from '../../core/routes';

@Component({
  selector: 'app-tag-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, PosterCardComponent, MatProgressSpinnerModule, MatIconModule, MatButtonModule],
  template: `
    <div class="content-page">
      @if (loading()) {
        <div class="loading-container">
          <mat-spinner aria-label="Loading" diameter="40" />
        </div>
      } @else if (error()) {
        <p class="error-message">{{ error() }}</p>
      } @else if (tag(); as t) {
        <div class="header">
          <span class="tag-badge"
                [style.background-color]="t.bg_color"
                [style.color]="t.text_color">{{ t.name }}</span>
          <span class="title-count">{{ total() }} title{{ total() === 1 ? '' : 's' }}</span>
        </div>

        @if (features.isAdmin()) {
          <div class="admin-add-row">
            <input class="add-input" type="text"
                   placeholder="Search the catalog for titles to add to this tag…"
                   aria-label="Search the catalog for titles to add to this tag"
                   [value]="searchQuery()" (input)="updateSearch($event)" (keydown.enter)="searchTitles()" />
            @if (searchResults().length > 0) {
              <div class="search-results">
                @for (r of searchResults(); track r.title_id) {
                  <div class="search-result-row">
                    <span>{{ r.title_name }}</span>
                    @if (r.release_year) { <span class="result-year">({{ r.release_year }})</span> }
                    @if (mediaTypeLabel(r.media_type); as mt) {
                      <span class="result-media-type">{{ mt }}</span>
                    }
                    <button mat-flat-button color="primary" class="add-btn" (click)="addTitle(r.title_id)">Add</button>
                  </div>
                }
              </div>
            }
          </div>
        }

        <div class="grid tag-titles-grid">
          @for (title of titles(); track title.title_id) {
            <app-poster-card [name]="title.title_name" [imageUrl]="title.poster_url"
                             [link]="routes.title(title.title_id)" [square]="title.media_type === 'ALBUM'"
                             [placeholderIcon]="mediaIcon(title.media_type)"
                             [playable]="!!title.playable" [progress]="title.progress_fraction"
                             [meta]="mediaTypeLabel(title.media_type)" [subtitle]="subline(title)">
              @if (features.isAdmin()) {
                <button cardAction class="remove-btn" type="button" (click)="removeTitle(title.title_id)"
                        aria-label="Remove from tag">
                  <mat-icon>close</mat-icon>
                </button>
              }
            </app-poster-card>
          }
        </div>

        @if (tracks().length > 0) {
          <section class="tagged-tracks-section">
            <div class="tagged-tracks-header">
              <h2>Tagged tracks ({{ tracks().length }})</h2>
              <button mat-flat-button color="primary" type="button" (click)="playAllTaggedTracks()">
                <mat-icon>play_arrow</mat-icon>
                Play all
              </button>
            </div>
            <ol class="tagged-tracks-list">
              @for (tr of tracks(); track tr.track_id) {
                <li class="tagged-track">
                  @if (tr.poster_url) {
                    <img class="track-thumb" [src]="tr.poster_url" [alt]="tr.title_name" />
                  } @else {
                    <div class="track-thumb track-thumb-placeholder"><mat-icon>music_note</mat-icon></div>
                  }
                  <div class="track-text">
                    <span class="track-name">{{ tr.track_name }}</span>
                    <a class="track-album" [routerLink]="routes.title(tr.title_id)">
                      {{ tr.title_name }}@if (tr.artist_name) { <span class="track-artist"> · {{ tr.artist_name }}</span> }
                    </a>
                  </div>
                  <span class="track-duration">{{ formatTrackDuration(tr.duration_seconds) }}</span>
                  <button mat-icon-button type="button" (click)="playOneTaggedTrack(tr)"
                          aria-label="Play this track" [disabled]="!tr.playable">
                    <mat-icon>play_circle</mat-icon>
                  </button>
                </li>
              }
            </ol>
          </section>
        }
      }
    </div>
  `,
  styles: `
    .content-page { padding: 1.5rem; max-width: 1200px; margin: 0 auto; }
    .loading-container { display: flex; justify-content: center; padding: 4rem; }
    .error-message { color: var(--mat-sys-error); text-align: center; padding: 2rem; }

    .header { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1rem; }
    .tag-badge {
      display: inline-block; padding: 6px 16px;
      border-radius: var(--r-pill, 9999px); font-weight: 500; font-size: 1.125rem;
    }
    .title-count { font-size: 0.8125rem; color: var(--mat-sys-on-surface-variant); }

    // Title cards: poster-card in the global .grid (styles/_grid.scss),
    // the media type as card meta, the admin remove control as a card action.
    .result-media-type {
      font-size: 0.6875rem; color: var(--mat-sys-on-surface-variant); text-transform: uppercase; margin: 0 0.25rem;
    }

    .tagged-tracks-section { margin-bottom: 1.5rem; }
    .tagged-tracks-header {
      display: flex; align-items: center; justify-content: space-between;
      margin-bottom: 0.5rem;
    }
    .tagged-tracks-header h2 { margin: 0; font-size: 1rem; }
    .tagged-tracks-list {
      list-style: none; padding: 0; margin: 0;
      display: flex; flex-direction: column; gap: 0.25rem;
    }
    .tagged-track {
      display: grid;
      grid-template-columns: 3rem 1fr auto auto;
      align-items: center; gap: 0.75rem;
      padding: 0.4rem 0.6rem;
      border-radius: 8px;
      background: color-mix(in srgb, var(--mat-sys-on-surface) 3%, transparent);
    }
    .track-thumb {
      width: 3rem; height: 3rem; border-radius: 4px;
      object-fit: cover;
      background: color-mix(in srgb, var(--mat-sys-on-surface) 5%, transparent);
    }
    .track-thumb-placeholder { display: flex; align-items: center; justify-content: center; color: var(--mat-sys-on-surface-variant); }
    .track-text { min-width: 0; display: flex; flex-direction: column; }
    .track-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .track-album {
      font-size: 0.8125rem; color: var(--mat-sys-on-surface-variant); text-decoration: none;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .track-album:hover { text-decoration: underline; }
    .track-duration { color: var(--mat-sys-on-surface-variant); font-variant-numeric: tabular-nums; font-size: 0.875rem; }

    .admin-add-row { margin-bottom: 1rem; position: relative; }
    .add-input {
      width: 100%; max-width: 400px;
      background: color-mix(in srgb, var(--mat-sys-on-surface) 8%, transparent);
      border: 1px solid var(--mat-sys-outline-variant); border-radius: 4px;
      color: inherit; padding: 8px 12px; font-size: 0.875rem; outline: none; box-sizing: border-box;
    }
    .add-input:focus { border-color: var(--mat-sys-primary); }
    .add-input::placeholder { color: var(--mat-sys-on-surface-variant); opacity: 0.7; }
    .search-results {
      position: absolute; z-index: 10;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface);
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 8px;
      box-shadow: 0 4px 16px rgba(0,0,0,0.25);
      max-width: 400px; width: 100%; overflow: hidden;
    }
    .search-result-row {
      display: flex; align-items: center; gap: 0.5rem; padding: 8px 12px;
      font-size: 0.8125rem;
      color: var(--mat-sys-on-surface);
    }
    .search-result-row:hover {
      background: color-mix(in srgb, var(--mat-sys-on-surface) 8%, transparent);
    }
    .result-year { color: var(--mat-sys-on-surface-variant); }
    .add-btn { margin-left: auto; font-size: 0.75rem !important; padding: 0 8px !important; min-height: 24px !important; line-height: 24px !important; }
    .remove-btn {
      position: absolute; top: 4px; right: 4px;
      background: var(--c-scrim-strong, #000000b2); border: none; border-radius: 50%;
      width: 32px; height: 32px; display: flex; align-items: center; justify-content: center;
      cursor: pointer; color: #fff; padding: 0; opacity: 0;
      transition: opacity 0.15s;
      mat-icon { font-size: 18px; width: 18px; height: 18px; }
      &:focus-visible { opacity: 1; outline: 2px solid var(--c-accent, var(--mat-sys-primary)); outline-offset: 2px; }
    }
    app-poster-card:hover .remove-btn { opacity: 1; }
    @media (hover: none) { .remove-btn { opacity: 1; } }
  `,
})
export class TagDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly catalog = inject(CatalogService);
  private readonly http = inject(HttpClient);
  readonly features = inject(FeatureService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly tag = signal<TagCard | null>(null);
  readonly titles = signal<TitleCard[]>([]);
  readonly total = signal(0);
  readonly searchQuery = signal('');
  readonly searchResults = signal<{ title_id: number; title_name: string; release_year: number | null; media_type?: string }[]>([]);
  readonly tracks = signal<TaggedTrackCard[]>([]);
  private tagId = 0;
  private readonly queue = inject(PlaybackQueueService);

  /** Queue-and-play all tagged tracks. */
  playAllTaggedTracks(): void {
    const list = this.tracks().filter(t => t.playable);
    if (list.length === 0) return;
    this.queue.playTracks(list.map(this.toQueued), 0);
  }

  /** Queue all tagged tracks, start at the clicked row. */
  playOneTaggedTrack(row: TaggedTrackCard): void {
    const list = this.tracks().filter(t => t.playable);
    const idx = list.findIndex(t => t.track_id === row.track_id);
    if (idx < 0) return;
    this.queue.playTracks(list.map(this.toQueued), idx);
  }

  private toQueued(t: TaggedTrackCard): QueuedTrack {
    return {
      trackId: t.track_id,
      trackName: t.track_name,
      durationSeconds: t.duration_seconds,
      albumTitleId: t.title_id,
      albumName: t.title_name ?? '',
      albumPosterUrl: t.poster_url,
      primaryArtistName: t.artist_name ?? null,
    };
  }

  formatTrackDuration(seconds: number | null): string {
    if (!seconds || seconds < 1) return '—';
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${s.toString().padStart(2, '0')}`;
  }

  /** Short label for the media-type badge on each card. Empty for unknown. */
  mediaTypeLabel(mt: string | undefined): string {
    switch (mt) {
      case 'ALBUM': return 'Album';
      case 'BOOK':  return 'Book';
      case 'TV':    return 'TV';
      case 'MOVIE': return 'Movie';
      case 'PERSONAL': return 'Personal';
      default: return '';
    }
  }

  /** Material icon name for the placeholder when the title has no poster. */
  mediaIcon(mt: string | undefined): string {
    switch (mt) {
      case 'ALBUM': return 'album';
      case 'BOOK':  return 'menu_book';
      case 'TV':    return 'tv';
      case 'PERSONAL': return 'family_restroom';
      default: return 'movie';
    }
  }

  /** Per-card sub-line: artist for albums, author for books, year otherwise. */
  subline(t: TitleCard): string {
    if (t.media_type === 'ALBUM') {
      return [t.artist_name, t.release_year].filter(Boolean).join(' · ');
    }
    if (t.media_type === 'BOOK') {
      return [t.author_name, t.release_year].filter(Boolean).join(' · ');
    }
    return t.release_year != null ? String(t.release_year) : '';
  }

  async ngOnInit(): Promise<void> {
    this.tagId = Number(this.route.snapshot.paramMap.get('tagId'));
    const id = this.tagId;
    if (!id) {
      this.error.set('Invalid tag ID');
      this.loading.set(false);
      return;
    }
    try {
      const data = await this.catalog.getTagDetail(id);
      this.tag.set(data.tag);
      this.titles.set(data.titles);
      this.total.set(data.total);
      this.tracks.set(data.tracks ?? []);
    } catch {
      this.error.set('Failed to load tag');
    } finally {
      this.loading.set(false);
    }
  }

  updateSearch(event: Event): void {
    this.searchQuery.set((event.target as HTMLInputElement).value);
    if (this.searchQuery().trim().length >= 2) this.searchTitles();
    else this.searchResults.set([]);
  }

  async searchTitles(): Promise<void> {
    const q = this.searchQuery().trim();
    if (q.length < 2) return;
    try {
      const d = await firstValueFrom(this.http.get<{ results: { title_id: number; title_name: string; release_year: number | null }[] }>(
        `/api/v2/catalog/tags/${this.tagId}/search-titles`, { params: { q } }));
      this.searchResults.set(d.results);
    } catch { /* ignore */ }
  }

  async addTitle(titleId: number): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/catalog/tags/${this.tagId}/titles/${titleId}`, {}));
    this.searchResults.set([]);
    this.searchQuery.set('');
    await this.refreshTag();
  }

  async removeTitle(titleId: number): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v2/catalog/tags/${this.tagId}/titles/${titleId}`));
    await this.refreshTag();
  }

  private async refreshTag(): Promise<void> {
    try {
      const data = await this.catalog.getTagDetail(this.tagId);
      this.tag.set(data.tag);
      this.titles.set(data.titles);
      this.total.set(data.total);
    } catch { /* ignore */ }
  }
}
