import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, CollectionCard } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';

@Component({
  selector: 'app-collections',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatProgressSpinnerModule],
  template: `
    <div class="content-page">
      @if (loading()) {
        <div class="loading-container">
          <mat-spinner diameter="40" />
        </div>
      } @else if (error()) {
        <p class="error-message">{{ error() }}</p>
      } @else {
        <span class="status-label">{{ total() }} collections</span>
        <div class="poster-grid">
          @for (c of collections(); track c.id) {
            <a class="poster-card" [routerLink]="routes.collection(c.id)">
              <div class="poster-wrapper">
                @if (c.poster_url) {
                  <img [src]="c.poster_url" [alt]="c.name" class="poster-img" />
                } @else {
                  <div class="poster-placeholder"></div>
                }
                <div class="count-badge">{{ c.owned_count }} / {{ c.total_parts }}</div>
              </div>
              <span class="poster-title">{{ c.name }}</span>
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
    .status-label { display: block; font-size: 0.8125rem; opacity: 0.5; margin-bottom: 0.75rem; }

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
    .poster-img { display: block; width: 100%; height: 100%; object-fit: cover; }
    .poster-placeholder { width: 100%; height: 100%; }
    .count-badge {
      position: absolute; bottom: 6px; right: 6px;
      background: rgba(0,0,0,0.7); color: white;
      font-size: 0.6875rem; padding: 2px 8px;
      border-radius: 9999px; font-weight: 600;
    }
    .poster-title {
      font-size: 0.75rem; margin-top: 0.25rem;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
  `,
})
export class CollectionsComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly collections = signal<CollectionCard[]>([]);
  readonly total = signal(0);

  async ngOnInit(): Promise<void> {
    try {
      const data = await this.catalog.getCollections();
      this.collections.set(data.collections);
      this.total.set(data.total);
    } catch {
      this.error.set('Failed to load collections');
    } finally {
      this.loading.set(false);
    }
  }
}
