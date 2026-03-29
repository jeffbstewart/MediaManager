import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, TagCard, TitleCard } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';

@Component({
  selector: 'app-tag-detail',
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
      } @else if (tag(); as t) {
        <div class="header">
          <span class="tag-badge"
                [style.background-color]="t.bg_color"
                [style.color]="t.text_color">{{ t.name }}</span>
          <span class="title-count">{{ total() }} title{{ total() === 1 ? '' : 's' }}</span>
        </div>

        <div class="poster-grid">
          @for (title of titles(); track title.title_id) {
            <a class="poster-card" [routerLink]="routes.title(title.title_id)">
              <div class="poster-wrapper">
                @if (title.poster_url) {
                  <img [src]="title.poster_url" [alt]="title.title_name" class="poster-img" />
                } @else {
                  <div class="poster-placeholder"></div>
                }
                @if (title.playable) {
                  <div class="playable-badge"><div class="play-triangle"></div></div>
                }
                @if (title.progress_fraction) {
                  <div class="progress-track">
                    <div class="progress-fill" [style.width.%]="title.progress_fraction! * 100"></div>
                  </div>
                }
              </div>
              <span class="poster-title">{{ title.title_name }}</span>
              @if (title.release_year) {
                <span class="poster-meta">{{ title.release_year }}</span>
              }
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
  `,
})
export class TagDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly catalog = inject(CatalogService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly tag = signal<TagCard | null>(null);
  readonly titles = signal<TitleCard[]>([]);
  readonly total = signal(0);

  async ngOnInit(): Promise<void> {
    const id = Number(this.route.snapshot.paramMap.get('tagId'));
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
    } catch {
      this.error.set('Failed to load tag');
    } finally {
      this.loading.set(false);
    }
  }
}
