import { Component, input, inject, signal, computed, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AppRoutes } from '../../core/routes';
import {
  CatalogService,
  TitleCard,
  MediaType,
  SortMode,
} from '../../core/catalog.service';

@Component({
  selector: 'app-title-grid',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    MatChipsModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './title-grid.html',
  styleUrl: './title-grid.scss',
})
export class TitleGridComponent implements OnInit {
  readonly mediaType = input.required<MediaType>();
  readonly label = input.required<string>();

  private readonly catalog = inject(CatalogService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly titles = signal<TitleCard[]>([]);
  readonly total = signal(0);
  readonly availableRatings = signal<string[]>([]);

  readonly playableOnly = signal(true);
  readonly selectedRatings = signal<Set<string>>(new Set());
  readonly sortMode = signal<SortMode>('name');

  readonly sortOptions: { value: SortMode; label: string }[] = [
    { value: 'name', label: 'Name' },
    { value: 'year', label: 'Year' },
    { value: 'recent', label: 'Recent' },
    { value: 'popular', label: 'Popular' },
  ];

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async togglePlayable(): Promise<void> {
    this.playableOnly.update(v => !v);
    await this.refresh();
  }

  async toggleRating(rating: string): Promise<void> {
    this.selectedRatings.update(set => {
      const next = new Set(set);
      if (next.has(rating)) {
        next.delete(rating);
      } else {
        next.add(rating);
      }
      return next;
    });
    await this.refresh();
  }

  async clearRatings(): Promise<void> {
    this.selectedRatings.set(new Set());
    await this.refresh();
  }

  async setSort(mode: SortMode): Promise<void> {
    this.sortMode.set(mode);
    await this.refresh();
  }

  private async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const ratings = this.selectedRatings();
      const data = await this.catalog.getTitles({
        mediaType: this.mediaType(),
        sort: this.sortMode(),
        ratings: ratings.size > 0 ? [...ratings] : undefined,
        playableOnly: this.playableOnly(),
      });
      this.titles.set(data.titles);
      this.total.set(data.total);
      this.availableRatings.set(data.available_ratings);
    } catch {
      this.error.set('Failed to load titles');
    } finally {
      this.loading.set(false);
    }
  }
}
