import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { CatalogService, TagCard, TaggedTrackCard, TitleCard } from '../../core/catalog.service';
import { FeatureService } from '../../core/feature.service';
import { PlaybackQueueService, QueuedTrack } from '../../core/playback-queue.service';
import { AppRoutes } from '../../core/routes';

@Component({
  selector: 'app-tag-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatProgressSpinnerModule, MatIconModule, MatButtonModule],
  template: `
    <div class="content-page">
      @if (loading()) {
        <div class="loading-container">
          <mat-spinner diameter="40" />
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
            <input class="add-input" type="text" placeholder="Search titles to add..."
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
                    <a class="track-album" [routerLink]="routes.title(tr.title_id)">{{ tr.title_name }}</a>
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

        <div class="poster-grid">
          @for (title of titles(); track title.title_id) {
            <a class="poster-card"
               [class.album-card]="title.media_type === 'ALBUM'"
               [routerLink]="routes.title(title.title_id)">
              <div class="poster-wrapper" [class.poster-square]="title.media_type === 'ALBUM'">
                @if (title.poster_url) {
                  <img [src]="title.poster_url" [alt]="title.title_name" class="poster-img" />
                } @else {
                  <div class="poster-placeholder">
                    <mat-icon>{{ mediaIcon(title.media_type) }}</mat-icon>
                  </div>
                }
                @if (title.playable) {
                  <div class="playable-badge"><div class="play-triangle"></div></div>
                }
                @if (mediaTypeLabel(title.media_type); as mt) {
                  <span class="media-type-pill">{{ mt }}</span>
                }
                @if (features.isAdmin()) {
                  <button class="remove-btn" (click)="removeTitle(title.title_id); $event.preventDefault(); $event.stopPropagation()"
                          aria-label="Remove from tag">
                    <mat-icon>close</mat-icon>
                  </button>
                }
                @if (title.progress_fraction) {
                  <div class="progress-track">
                    <div class="progress-fill" [style.width.%]="title.progress_fraction! * 100"></div>
                  </div>
                }
              </div>
              <span class="poster-title">{{ title.title_name }}</span>
              <span class="poster-meta">{{ subline(title) }}</span>
            </a>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .content-page { padding: 1.5rem; max-width: 1200px; margin: 0 auto; }
    .loading-container { display: flex; justify-content: center; padding: 4rem; }
    .error-message { color: var(--mat-sys-error, #f44336); text-align: center; padding: 2rem; }

    .header { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1rem; }
    .tag-badge {
      display: inline-block; padding: 6px 16px;
      border-radius: 9999px; font-weight: 500; font-size: 1.125rem;
    }
    .title-count { font-size: 0.8125rem; opacity: 0.5; }

    .poster-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
      gap: 1rem;
    }
    .poster-card {
      display: flex; flex-direction: column;
      text-decoration: none; color: inherit; cursor: pointer; text-align: center;
    }
    .poster-wrapper {
      position: relative; width: 100%; aspect-ratio: 2/3;
      border-radius: 8px; overflow: hidden; background: rgba(255,255,255,0.05);
    }
    .poster-wrapper.poster-square { aspect-ratio: 1/1; }
    .poster-placeholder { display: flex; align-items: center; justify-content: center; opacity: 0.35; }
    .poster-placeholder mat-icon { font-size: 3rem; width: 3rem; height: 3rem; }
    .media-type-pill {
      position: absolute; top: 6px; left: 6px;
      background: rgba(0,0,0,0.65); color: rgba(255,255,255,0.9);
      font-size: 0.625rem; padding: 2px 6px; border-radius: 4px;
      text-transform: uppercase; letter-spacing: 0.03em;
    }
    .result-media-type {
      font-size: 0.6875rem; opacity: 0.6; text-transform: uppercase; margin: 0 0.25rem;
    }

    .tagged-tracks-section { margin-bottom: 1.5rem; }
    .tagged-tracks-header {
      display: flex; align-items: center; justify-content: space-between;
      margin-bottom: 0.5rem;
    }
    .tagged-tracks-header h2 { margin: 0; font-size: 1rem; opacity: 0.85; }
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
      background: rgba(255, 255, 255, 0.03);
    }
    .track-thumb {
      width: 3rem; height: 3rem; border-radius: 4px;
      object-fit: cover; background: rgba(255, 255, 255, 0.05);
    }
    .track-thumb-placeholder { display: flex; align-items: center; justify-content: center; opacity: 0.4; }
    .track-text { min-width: 0; display: flex; flex-direction: column; }
    .track-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .track-album {
      font-size: 0.8125rem; opacity: 0.7; text-decoration: none; color: inherit;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .track-album:hover { text-decoration: underline; }
    .track-duration { opacity: 0.7; font-variant-numeric: tabular-nums; font-size: 0.875rem; }
    .poster-img { display: block; width: 100%; height: 100%; object-fit: cover; }
    .poster-placeholder { width: 100%; height: 100%; }
    .playable-badge {
      position: absolute; bottom: 6px; right: 6px;
      width: 24px; height: 24px; background: rgba(0,0,0,0.6);
      border-radius: 50%; display: flex; align-items: center; justify-content: center;
    }
    .play-triangle {
      width: 0; height: 0; border-style: solid;
      border-width: 5px 0 5px 9px;
      border-color: transparent transparent transparent white;
      margin-left: 2px;
    }
    .progress-track {
      position: absolute; bottom: 0; left: 0; width: 100%; height: 3px;
      background: rgba(0,0,0,0.5);
    }
    .progress-fill { height: 100%; background: var(--mat-sys-primary, #bb86fc); }
    .poster-title {
      font-size: 0.75rem; margin-top: 0.25rem;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .poster-meta { font-size: 0.6875rem; opacity: 0.5; }

    .admin-add-row { margin-bottom: 1rem; position: relative; }
    .add-input {
      width: 100%; max-width: 400px; background: rgba(255,255,255,0.08);
      border: 1px solid rgba(255,255,255,0.15); border-radius: 4px;
      color: white; padding: 8px 12px; font-size: 0.875rem; outline: none; box-sizing: border-box;
    }
    .add-input:focus { border-color: var(--mat-sys-primary, #bb86fc); }
    .add-input::placeholder { color: rgba(255,255,255,0.35); }
    .search-results {
      position: absolute; z-index: 10; background: #2a2a2a; border-radius: 8px;
      box-shadow: 0 4px 16px rgba(0,0,0,0.5); max-width: 400px; width: 100%; overflow: hidden;
    }
    .search-result-row {
      display: flex; align-items: center; gap: 0.5rem; padding: 8px 12px;
      font-size: 0.8125rem;
    }
    .search-result-row:hover { background: rgba(255,255,255,0.05); }
    .result-year { opacity: 0.4; }
    .add-btn { margin-left: auto; font-size: 0.75rem !important; padding: 0 8px !important; min-height: 24px !important; line-height: 24px !important; }
    .remove-btn {
      position: absolute; top: 4px; right: 4px;
      background: rgba(0,0,0,0.6); border: none; border-radius: 50%;
      width: 24px; height: 24px; display: flex; align-items: center; justify-content: center;
      cursor: pointer; color: rgba(255,255,255,0.7); padding: 0; opacity: 0;
      transition: opacity 0.15s;
      mat-icon { font-size: 16px; width: 16px; height: 16px; }
    }
    .poster-wrapper:hover .remove-btn { opacity: 1; }
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
      primaryArtistName: null,
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
