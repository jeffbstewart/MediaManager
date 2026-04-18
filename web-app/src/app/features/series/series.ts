import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { CatalogService, BookSeriesDetail, BookSeriesMissingVolume } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';
import { WishInterstitialService } from '../../core/wish-interstitial.service';

@Component({
  selector: 'app-series',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatProgressSpinnerModule, MatButtonModule],
  templateUrl: './series.html',
  styleUrl: './series.scss',
})
export class SeriesComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly catalog = inject(CatalogService);
  private readonly wishInterstitial = inject(WishInterstitialService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly series = signal<BookSeriesDetail | null>(null);
  readonly bulkAdding = signal(false);
  readonly bulkMessage = signal('');

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

  async toggleWish(vol: BookSeriesMissingVolume): Promise<void> {
    const s = this.series();
    if (!s) return;
    if (vol.already_wished) {
      await this.catalog.removeBookWish(vol.ol_work_id);
      vol.already_wished = false;
    } else {
      if (await this.wishInterstitial.needsInterstitial()) {
        if (!confirm('Your wish list entries are visible to administrators to help inform purchase decisions. Continue?')) return;
        this.wishInterstitial.acknowledge();
      }
      await this.catalog.addBookWish({
        ol_work_id: vol.ol_work_id,
        title: vol.title,
        author: s.author?.name ?? null,
        series_id: s.id,
        series_number: vol.series_number,
      });
      vol.already_wished = true;
    }
    this.series.update(v => v ? { ...v } : v);
  }

  async wishlistAllGaps(): Promise<void> {
    const s = this.series();
    if (!s) return;
    if (await this.wishInterstitial.needsInterstitial()) {
      if (!confirm('Your wish list entries are visible to administrators to help inform purchase decisions. Continue?')) return;
      this.wishInterstitial.acknowledge();
    }
    this.bulkAdding.set(true);
    try {
      const result = await this.catalog.wishlistSeriesGaps(s.id);
      this.bulkMessage.set(
        result.error ? result.error :
          `Added ${result.added} book${result.added === 1 ? '' : 's'} to your wish list` +
          (result.already_wished > 0 ? ` (${result.already_wished} already there).` : '.')
      );
      // Refresh to reflect new wish state on each missing volume.
      this.series.set(await this.catalog.getSeriesDetail(s.id));
    } finally {
      this.bulkAdding.set(false);
    }
  }
}
