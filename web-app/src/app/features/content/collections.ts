import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { PosterCardComponent } from '../../shared/media';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, CollectionCard } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';

@Component({
  selector: 'app-collections',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PosterCardComponent, MatProgressSpinnerModule],
  template: `
    <div class="content-page">
      @if (loading()) {
        <div class="loading-container">
          <mat-spinner aria-label="Loading" diameter="40" />
        </div>
      } @else if (error()) {
        <p class="error-message">{{ error() }}</p>
      } @else {
        <span class="status-label">{{ total() }} collections</span>
        <div class="grid collections-grid">
          @for (c of collections(); track c.id) {
            <app-poster-card [name]="c.name" [imageUrl]="c.poster_url" [link]="routes.collection(c.id)" [lazy]="false">
              <div cardArt class="count-badge">{{ c.owned_count }} / {{ c.total_parts }}</div>
            </app-poster-card>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .content-page { padding: 1.5rem; max-width: 1200px; margin: 0 auto; }
    .loading-container { display: flex; justify-content: center; padding: 4rem; }
    .error-message { color: var(--mat-sys-error); text-align: center; padding: 2rem; }
    .status-label { display: block; font-size: 0.8125rem; color: var(--c-text-3, var(--mat-sys-on-surface-variant)); margin-bottom: 0.75rem; }

    // Poster cards in the global .grid (styles/_grid.scss).
    .count-badge {
      position: absolute; bottom: 6px; right: 6px;
      background: var(--c-scrim-strong, #000000b2); color: #fff;
      font: 600 12px/16px var(--f-body, inherit); padding: 2px 8px;
      border-radius: var(--r-pill, 9999px);
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
