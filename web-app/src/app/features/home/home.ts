import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AppRoutes } from '../../core/routes';
import {
  CatalogService,
  HomeFeed,
} from '../../core/catalog.service';

@Component({
  selector: 'app-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    MatIconModule,
    MatButtonModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class HomeComponent implements OnInit {
  private readonly catalog = inject(CatalogService);

  readonly routes = AppRoutes;
  readonly loading = signal(true);
  readonly feed = signal<HomeFeed | null>(null);
  readonly error = signal('');

  async ngOnInit(): Promise<void> {
    try {
      const data = await this.catalog.getHomeFeed();
      this.feed.set(data);
    } catch {
      this.error.set('Failed to load home feed');
    } finally {
      this.loading.set(false);
    }
  }

  async dismissProgress(event: Event, transcodeId: number): Promise<void> {
    event.preventDefault();
    event.stopPropagation();
    try {
      await this.catalog.clearProgress(transcodeId);
      this.feed.update(f => f ? {
        ...f,
        continue_watching: f.continue_watching.filter(i => i.transcode_id !== transcodeId),
      } : f);
    } catch { /* silently fail — item stays in carousel */ }
  }

  async dismissMissingSeasons(event: Event, titleId: number): Promise<void> {
    event.preventDefault();
    event.stopPropagation();
    try {
      await this.catalog.dismissMissingSeasons(titleId);
      this.feed.update(f => f ? {
        ...f,
        missing_seasons: f.missing_seasons.filter(i => i.title_id !== titleId),
      } : f);
    } catch { /* silently fail — item stays in carousel */ }
  }
}
