import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, BookSeriesDetail } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';

@Component({
  selector: 'app-series',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './series.html',
  styleUrl: './series.scss',
})
export class SeriesComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly catalog = inject(CatalogService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly series = signal<BookSeriesDetail | null>(null);

  async ngOnInit(): Promise<void> {
    const id = Number(this.route.snapshot.paramMap.get('seriesId'));
    if (!id) {
      this.error.set('Invalid series ID');
      this.loading.set(false);
      return;
    }
    try {
      this.series.set(await this.catalog.getSeriesDetail(id));
    } catch {
      this.error.set('Failed to load series details');
    } finally {
      this.loading.set(false);
    }
  }
}
