import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CategoryTileComponent } from '../../shared/media';
import { CatalogService, TagCard } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';

@Component({
  selector: 'app-tags',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CategoryTileComponent, MatProgressSpinnerModule],
  template: `
    <div class="content-page">
      @if (loading()) {
        <div class="loading-container">
          <mat-spinner aria-label="Loading" diameter="40" />
        </div>
      } @else if (error()) {
        <p class="error-message">{{ error() }}</p>
      } @else {
        <span class="status-label">{{ total() }} tags</span>
        <div class="grid tag-grid">
          @for (tag of tags(); track tag.id) {
            <app-category-tile [label]="tag.name" [link]="routes.tag(tag.id)"
                               [colors]="tileColors(tag)" [textColor]="tag.text_color">
              <span class="tag-count">{{ tag.title_count }} {{ tag.title_count === 1 ? 'title' : 'titles' }}</span>
            </app-category-tile>
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

    // Tag tiles (STYLE-SPEC 3.7) in the global .grid (styles/_grid.scss).
    .tag-count { font: 600 12px/16px var(--f-body, inherit); }
  `,
})
export class TagsComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly tags = signal<TagCard[]>([]);
  readonly total = signal(0);

  /** The tag's own color, fading to a darker shade of it. */
  tileColors(tag: TagCard): readonly [string, string] {
    return [tag.bg_color, `color-mix(in srgb, ${tag.bg_color} 75%, #000)`];
  }

  async ngOnInit(): Promise<void> {
    try {
      const data = await this.catalog.getTags();
      this.tags.set(data.tags);
      this.total.set(data.total);
    } catch {
      this.error.set('Failed to load tags');
    } finally {
      this.loading.set(false);
    }
  }
}
