import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { Router } from '@angular/router';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, TvChannelInfo } from '../../core/catalog.service';

@Component({
  selector: 'app-live-tv',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatProgressSpinnerModule],
  templateUrl: './live-tv.html',
  styleUrl: './live-tv.scss',
})
export class LiveTvComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  private readonly router = inject(Router);

  readonly loading = signal(true);
  readonly error = signal('');
  readonly channels = signal<TvChannelInfo[]>([]);

  async ngOnInit(): Promise<void> {
    try {
      const data = await this.catalog.getTvChannels();
      this.channels.set(data.channels);
    } catch (e: unknown) {
      const status = (e as { status?: number })?.status;
      if (status === 403) {
        this.error.set('Live TV is not available for your account.');
      } else {
        this.error.set('Failed to load channels');
      }
    } finally {
      this.loading.set(false);
    }
  }

  watchChannel(channel: TvChannelInfo): void {
    this.router.navigate(['/live-tv', channel.id], {
      queryParams: { name: `${channel.guide_number} ${channel.guide_name}` },
    });
  }
}
