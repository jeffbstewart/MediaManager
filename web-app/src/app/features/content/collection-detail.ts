import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { PosterCardComponent } from '../../shared/media';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { CatalogService, CollectionDetail, CollectionPart } from '../../core/catalog.service';
import { WishInterstitialService } from '../../core/wish-interstitial.service';
import { AppRoutes } from '../../core/routes';

@Component({
  selector: 'app-collection-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PosterCardComponent, MatProgressSpinnerModule, MatIconModule],
  template: `
    <div class="content-page">
      @if (loading()) {
        <div class="loading-container">
          <mat-spinner aria-label="Loading" diameter="40" />
        </div>
      } @else if (error()) {
        <p class="error-message">{{ error() }}</p>
      } @else if (detail(); as d) {
        <h2 class="collection-title">{{ d.name }}</h2>
        <span class="status-label">{{ d.owned_count }} of {{ d.total_parts }} titles in your collection</span>

        <div class="grid collection-grid">
          @for (part of d.parts; track part.title_name) {
            @if (part.owned && part.title_id) {
              <app-poster-card [name]="part.title_name" [imageUrl]="part.poster_url"
                               [link]="routes.title(part.title_id!)" [lazy]="false"
                               [playable]="!!part.playable" [progress]="part.progress_fraction"
                               [meta]="part.release_year" />
            } @else {
              <app-poster-card [name]="part.title_name" [imageUrl]="part.poster_url"
                               dim="art" [lazy]="false" subtitle="Not owned" [meta]="part.release_year">
                @if (part.tmdb_movie_id) {
                  <button cardAction class="wish-heart" type="button" [class.wished]="part.wished"
                          (click)="toggleWish(part)"
                          [attr.aria-label]="part.wished ? 'Remove from wish list' : 'Add to wish list'">
                    <mat-icon>{{ part.wished ? 'favorite' : 'favorite_border' }}</mat-icon>
                  </button>
                }
              </app-poster-card>
            }
          }
        </div>
      }
    </div>
  `,
  styles: `
    .content-page { padding: 1.5rem; max-width: 1200px; margin: 0 auto; }
    .loading-container { display: flex; justify-content: center; padding: 4rem; }
    .error-message { color: var(--mat-sys-error); text-align: center; padding: 2rem; }
    .collection-title { margin: 0 0 0.25rem; }
    .status-label { display: block; font-size: 0.8125rem; color: var(--c-text-3, var(--mat-sys-on-surface-variant)); margin-bottom: 0.75rem; }

    // Poster cards in the global .grid (styles/_grid.scss). An unowned
    // part dims its art and says so in the caption, like Similar Titles.
    .wish-heart {
      position: absolute; top: 4px; right: 4px;
      background: var(--c-scrim-strong, #000000b2); border: none; border-radius: 50%;
      width: 32px; height: 32px; display: flex; align-items: center; justify-content: center;
      cursor: pointer; color: #fff; padding: 0;
      &:focus-visible { outline: 2px solid var(--c-accent, var(--mat-sys-primary)); outline-offset: 2px; }
      &.wished { color: var(--mat-sys-error); }
      mat-icon { font-size: 18px; width: 18px; height: 18px; }
    }
  `,
})
export class CollectionDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly catalog = inject(CatalogService);
  private readonly http = inject(HttpClient);
  private readonly wishInterstitial = inject(WishInterstitialService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly detail = signal<CollectionDetail | null>(null);

  async ngOnInit(): Promise<void> {
    const id = Number(this.route.snapshot.paramMap.get('collectionId'));
    if (!id) {
      this.error.set('Invalid collection ID');
      this.loading.set(false);
      return;
    }
    try {
      this.detail.set(await this.catalog.getCollectionDetail(id));
    } catch {
      this.error.set('Failed to load collection');
    } finally {
      this.loading.set(false);
    }
  }

  async toggleWish(part: CollectionPart): Promise<void> {
    if (!part.tmdb_movie_id) return;

    if (!part.wished) {
      if (await this.wishInterstitial.needsInterstitial()) {
        if (!confirm('Your media wish list entries are visible to administrators to help inform media purchase decisions. Continue?')) return;
        this.wishInterstitial.acknowledge();
      }
    }

    if (part.wished) {
      // Find and cancel the wish — collection parts are always movies
      const wishList = await this.catalog.getWishList();
      const wish = wishList.media_wishes.find(
        w => w.tmdb_id === part.tmdb_movie_id && w.tmdb_media_type === 'MOVIE'
      );
      if (wish) await this.catalog.cancelWish(wish.id);
    } else {
      // Add wish — collection parts are always movies
      await this.catalog.addMediaWish({
        tmdb_id: part.tmdb_movie_id,
        media_type: 'MOVIE',
        title: part.title_name,
        poster_path: null,
        release_year: part.release_year,
        popularity: null,
      });
    }

    // Refresh to get updated wish status
    const id = this.detail()?.id;
    if (id) this.detail.set(await this.catalog.getCollectionDetail(id));
  }
}
