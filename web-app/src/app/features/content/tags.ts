import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, TagCard } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';

@Component({
  selector: 'app-tags',
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
        <span class="status-label">{{ total() }} tags</span>
        <div class="tag-grid">
          @for (tag of tags(); track tag.id) {
            <a class="tag-chip" [routerLink]="routes.tag(tag.id)"
               [style.background-color]="tag.bg_color"
               [style.color]="tag.text_color">
              <span class="tag-name">{{ tag.name }}</span>
              <span class="tag-count">({{ tag.title_count }})</span>
            </a>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .content-page { padding: 1.5rem; max-width: 1200px; margin: 0 auto; }
    .loading-container { display: flex; justify-content: center; padding: 4rem; }
    .error-message { color: var(--mat-sys-error); text-align: center; padding: 2rem; }
    .status-label { display: block; font-size: 0.8125rem; color: var(--mat-sys-on-surface-variant); margin-bottom: 0.75rem; }

    .tag-grid {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
    }
    .tag-chip {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
      padding: 6px 14px;
      border-radius: 9999px;
      font-weight: 500;
      font-size: 0.875rem;
      text-decoration: none;
      cursor: pointer;
      transition: opacity 0.15s;
    }
    .tag-chip:hover { opacity: 0.85; }
    .tag-count { font-size: 0.75rem; }
  `,
})
export class TagsComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly tags = signal<TagCard[]>([]);
  readonly total = signal(0);

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
