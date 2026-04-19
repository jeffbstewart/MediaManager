import { Component, ChangeDetectionStrategy, OnInit, signal, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, ArtistsListItem, ArtistsSortMode } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';

/**
 * Audio landing page — artist exploration grid. Replaced the original
 * album grid because the album list grew unwieldy as the catalog
 * scaled. Drilling through artist → existing artist detail page (which
 * already shows owned albums + MB discography) keeps the album view
 * one click away.
 */
@Component({
  selector: 'app-music',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, FormsModule, MatChipsModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './music.html',
  styleUrl: './music.scss',
})
export class MusicComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly artists = signal<ArtistsListItem[]>([]);
  readonly total = signal(0);

  readonly playableOnly = signal(true);
  readonly sortMode = signal<ArtistsSortMode>('albums');
  readonly query = signal('');

  readonly sortOptions: { value: ArtistsSortMode; label: string }[] = [
    { value: 'albums', label: 'Albums' },
    { value: 'name', label: 'Name' },
    { value: 'recent', label: 'Recent' },
  ];

  // Debounce window for the search input. Server-side filter is fast,
  // but typing a name still wants a beat between keystrokes to avoid
  // five round-trips for "abba".
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async togglePlayable(): Promise<void> {
    this.playableOnly.update(v => !v);
    await this.refresh();
  }

  async setSort(mode: ArtistsSortMode): Promise<void> {
    this.sortMode.set(mode);
    await this.refresh();
  }

  onQueryInput(value: string): void {
    this.query.set(value);
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => { void this.refresh(); }, 200);
  }

  async clearQuery(): Promise<void> {
    if (this.searchTimer) { clearTimeout(this.searchTimer); this.searchTimer = null; }
    this.query.set('');
    await this.refresh();
  }

  /** Hero image for a card: real headshot first, owned-album cover fallback. */
  cardImageUrl(a: ArtistsListItem): string | null {
    return a.headshot_url ?? a.fallback_poster_url;
  }

  /** Mat-icon name for the placeholder when neither image source is available. */
  placeholderIcon(a: ArtistsListItem): string {
    return a.artist_type === 'PERSON' ? 'person' : 'groups';
  }

  private async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const data = await this.catalog.listArtists({
        sort: this.sortMode(),
        q: this.query().trim() || undefined,
        playableOnly: this.playableOnly(),
      });
      this.artists.set(data.artists);
      this.total.set(data.total);
    } catch {
      this.error.set('Failed to load artists');
    } finally {
      this.loading.set(false);
    }
  }
}
