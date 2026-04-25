import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
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
  imports: [RouterLink, MatProgressSpinnerModule, MatIconModule],
  template: `
    <div class="content-page">
      @if (loading()) {
        <div class="loading-container">
          <mat-spinner diameter="40" />
        </div>
      } @else if (error()) {
        <p class="error-message">{{ error() }}</p>
      } @else if (detail(); as d) {
        <h2 class="collection-title">{{ d.name }}</h2>
        <span class="status-label">{{ d.owned_count }} of {{ d.total_parts }} titles in your collection</span>

        <div class="poster-grid">
          @for (part of d.parts; track part.title_name) {
            @if (part.owned && part.title_id) {
              <a class="poster-card" [routerLink]="routes.title(part.title_id!)">
                <div class="poster-wrapper">
                  @if (part.poster_url) {
                    <img [src]="part.poster_url" [alt]="part.title_name" class="poster-img" />
                  } @else {
                    <div class="poster-placeholder"></div>
                  }
                  @if (part.playable) {
                    <div class="playable-badge"><div class="play-triangle"></div></div>
                  }
                  @if (part.progress_fraction) {
                    <div class="progress-track">
                      <div class="progress-fill" [style.width.%]="part.progress_fraction * 100"></div>
                    </div>
                  }
                </div>
                <span class="poster-title">{{ part.title_name }}</span>
                @if (part.release_year) {
                  <span class="poster-meta">{{ part.release_year }}</span>
                }
              </a>
            } @else {
              <div class="poster-card unowned">
                <div class="poster-wrapper">
                  @if (part.poster_url) {
                    <img [src]="part.poster_url" [alt]="part.title_name" class="poster-img" />
                  } @else {
                    <div class="poster-placeholder not-owned-label">Not Owned</div>
                  }
                  @if (part.tmdb_movie_id) {
                    <button class="wish-heart" [class.wished]="part.wished"
                            (click)="toggleWish(part)"
                            [attr.aria-label]="part.wished ? 'Remove from wish list' : 'Add to wish list'">
                      <mat-icon>{{ part.wished ? 'favorite' : 'favorite_border' }}</mat-icon>
                    </button>
                  }
                </div>
                <span class="poster-title">{{ part.title_name }}</span>
                @if (part.release_year) {
                  <span class="poster-meta">{{ part.release_year }}</span>
                }
              </div>
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
    .status-label { display: block; font-size: 0.8125rem; color: var(--mat-sys-on-surface-variant); margin-bottom: 0.75rem; }

    .poster-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
      gap: 1rem;
    }
    .poster-card {
      display: flex; flex-direction: column;
      text-decoration: none; color: inherit; cursor: pointer; text-align: center;
    }
    .poster-card.unowned { opacity: 0.4; cursor: default; }
    .poster-wrapper {
      position: relative; width: 100%; aspect-ratio: 2/3;
      border-radius: 8px; overflow: hidden;
      background: color-mix(in srgb, var(--mat-sys-on-surface) 5%, transparent);
    }
    .poster-img { display: block; width: 100%; height: 100%; object-fit: cover; }
    .poster-placeholder { width: 100%; height: 100%; }
    .not-owned-label {
      display: flex; align-items: center; justify-content: center;
      font-size: 0.75rem; color: var(--mat-sys-on-surface-variant);
    }
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
    .progress-fill { height: 100%; background: var(--mat-sys-primary); }
    .poster-title {
      font-size: 0.75rem; margin-top: 0.25rem;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .poster-meta { font-size: 0.6875rem; color: var(--mat-sys-on-surface-variant); }
    .wish-heart {
      position: absolute; top: 4px; right: 4px;
      background: rgba(0,0,0,0.5); border: none; border-radius: 50%;
      width: 32px; height: 32px; display: flex; align-items: center; justify-content: center;
      cursor: pointer; color: white; padding: 0;
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
      await firstValueFrom(this.http.post('/api/v2/wishlist/add', {
        tmdb_id: part.tmdb_movie_id,
        media_type: 'MOVIE',
        title: part.title_name,
        poster_path: null,
        release_year: part.release_year,
        popularity: null,
      }));
    }

    // Refresh to get updated wish status
    const id = this.detail()?.id;
    if (id) this.detail.set(await this.catalog.getCollectionDetail(id));
  }
}
