import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import {
  CatalogService, MediaWish, TranscodeWish, TmdbSearchResultItem, BookWish, AlbumWish, tmdbImageUrl,
} from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';
import { WishInterstitialService } from '../../core/wish-interstitial.service';

@Component({
  selector: 'app-wishlist',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './wishlist.html',
  styleUrl: './wishlist.scss',
})
export class WishListComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  private readonly router = inject(Router);
  private readonly wishInterstitial = inject(WishInterstitialService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly mediaWishes = signal<MediaWish[]>([]);
  readonly transcodeWishes = signal<TranscodeWish[]>([]);
  readonly bookWishes = signal<BookWish[]>([]);
  readonly albumWishes = signal<AlbumWish[]>([]);
  readonly hasAnyMediaWish = signal(false);

  readonly searchQuery = signal('');
  readonly searching = signal(false);
  readonly searchResults = signal<TmdbSearchResultItem[]>([]);
  readonly searchVisible = signal(false);

  // First-wish interstitial
  readonly showInterstitial = signal(false);
  private pendingWishItem: TmdbSearchResultItem | null = null;

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const data = await this.catalog.getWishList();
      this.mediaWishes.set(data.media_wishes);
      this.transcodeWishes.set(data.transcode_wishes);
      this.bookWishes.set(data.book_wishes ?? []);
      this.albumWishes.set(data.album_wishes ?? []);
      this.hasAnyMediaWish.set(data.has_any_media_wish);
    } catch {
      this.error.set('Failed to load wish list');
    } finally {
      this.loading.set(false);
    }
  }

  async search(): Promise<void> {
    const q = this.searchQuery().trim();
    if (!q) return;
    this.searching.set(true);
    this.searchVisible.set(true);
    try {
      const data = await this.catalog.searchTmdb(q);
      this.searchResults.set(data.results);
    } catch {
      this.searchResults.set([]);
    } finally {
      this.searching.set(false);
    }
  }

  onSearchKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') this.search();
  }

  updateSearchQuery(event: Event): void {
    this.searchQuery.set((event.target as HTMLInputElement).value);
  }

  async addWish(item: TmdbSearchResultItem): Promise<void> {
    if (await this.wishInterstitial.needsInterstitial()) {
      this.pendingWishItem = item;
      this.showInterstitial.set(true);
      return;
    }
    await this.doAddWish(item);
  }

  async confirmInterstitial(): Promise<void> {
    this.wishInterstitial.acknowledge();
    this.showInterstitial.set(false);
    if (this.pendingWishItem) {
      await this.doAddWish(this.pendingWishItem);
      this.pendingWishItem = null;
    }
  }

  cancelInterstitial(): void {
    this.showInterstitial.set(false);
    this.pendingWishItem = null;
  }

  private async doAddWish(item: TmdbSearchResultItem): Promise<void> {
    await this.catalog.addMediaWish({
      tmdb_id: item.tmdb_id,
      title: item.title,
      media_type: item.media_type,
      poster_path: item.poster_path,
      release_year: item.release_year,
      popularity: item.popularity,
    });
    item.already_wished = true;
    this.searchResults.update(r => [...r]);
    await this.refresh();
  }

  async cancelMediaWish(wish: MediaWish): Promise<void> {
    await this.catalog.cancelWish(wish.id);
    await this.refresh();
  }

  async dismissMediaWish(wish: MediaWish): Promise<void> {
    await this.catalog.dismissWish(wish.id);
    if (wish.title_id) {
      this.router.navigate(['/title', wish.title_id]);
    } else {
      await this.refresh();
    }
  }

  async removeTranscodeWish(wish: TranscodeWish): Promise<void> {
    await this.catalog.removeTranscodeWish(wish.id);
    await this.refresh();
  }

  async removeBookWish(wish: BookWish): Promise<void> {
    await this.catalog.removeBookWish(wish.ol_work_id);
    await this.refresh();
  }

  async removeAlbumWish(wish: AlbumWish): Promise<void> {
    await this.catalog.removeAlbumWish(wish.release_group_id);
    await this.refresh();
  }

  /**
   * Compilation-aware subtitle for an album wish. Matches docs/MUSIC.md Q9:
   * compilation-flagged entries render "Compilation · year" rather than
   * leading with "Various Artists" or a long artist list.
   */
  albumWishSubtitle(wish: AlbumWish): string {
    if (wish.is_compilation) {
      return wish.year != null ? `Compilation \u00B7 ${wish.year}` : 'Compilation';
    }
    const parts: string[] = [];
    if (wish.primary_artist) parts.push(wish.primary_artist);
    if (wish.year != null) parts.push(String(wish.year));
    return parts.join(' \u00B7 ');
  }

  posterUrl(path: string): string { return tmdbImageUrl(path, 'w185')!; }

  lifecycleColor(stage: string): string {
    switch (stage) {
      case 'READY_TO_WATCH': return '#4caf50';
      case 'ON_NAS_PENDING_DESKTOP':
      case 'ORDERED': return 'var(--mat-sys-primary)';
      case 'IN_HOUSE_PENDING_NAS': return 'var(--mat-sys-primary)';
      case 'NOT_FEASIBLE':
      case 'WONT_ORDER': return 'var(--mat-sys-error)';
      case 'NEEDS_ASSISTANCE': return '#ffa500';
      // PENDING / WISHED_FOR fall here. Use on-surface-variant so
      // the badge passes AA against the page surface in both modes.
      default: return 'var(--mat-sys-on-surface-variant)';
    }
  }
}
