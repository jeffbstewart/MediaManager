import { Component, computed, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, TvChannelInfo } from '../../core/catalog.service';
import { MediaRowComponent } from '../../shared/media';

/** Guide group that shows every channel. */
const ALL_CHANNELS = 'All Channels';
/** Guide group for channels without a network affiliation. */
const INDEPENDENT = 'Independent';

/** Reception bucket (see `getTvChannels`) to the short label in the meta line. */
const RECEPTION_LABEL: Record<number, string> = { 1: 'SD', 2: 'HD', 3: 'FHD', 4: 'UHD' };

@Component({
  selector: 'app-live-tv',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, RouterLink, MediaRowComponent],
  templateUrl: './live-tv.html',
  styleUrl: './live-tv.scss',
})
export class LiveTvComponent implements OnInit {
  private readonly catalog = inject(CatalogService);

  readonly loading = signal(true);
  readonly error = signal('');
  readonly channels = signal<TvChannelInfo[]>([]);

  /** The channel shown in the live-top player box and info column. */
  readonly current = computed<TvChannelInfo | null>(() => this.channels()[0] ?? null);

  /** The selected guide group chip. */
  readonly group = signal(ALL_CHANNELS);

  /** Guide group chips: all channels, each affiliation, then independents. */
  readonly groups = computed<string[]>(() => {
    const affiliations = new Set<string>();
    let independent = false;
    for (const c of this.channels()) {
      const a = c.network_affiliation?.trim();
      if (!a || a === INDEPENDENT) independent = true;
      else affiliations.add(a);
    }
    return [ALL_CHANNELS, ...affiliations, ...(independent ? [INDEPENDENT] : [])];
  });

  /** Channels in the selected guide group. */
  readonly guideChannels = computed<TvChannelInfo[]>(() => {
    const g = this.group();
    const all = this.channels();
    if (g === ALL_CHANNELS) return all;
    if (g === INDEPENDENT) {
      return all.filter(c => !c.network_affiliation?.trim() || c.network_affiliation.trim() === INDEPENDENT);
    }
    return all.filter(c => c.network_affiliation?.trim() === g);
  });

  readonly currentQuality = computed(() => {
    const c = this.current();
    if (!c) return '';
    return RECEPTION_LABEL[Math.min(Math.max(c.reception_quality, 1), 4)] ?? 'SD';
  });

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

  /** Query parameters the player route reads for its top bar label. */
  watchParams(channel: TvChannelInfo): { name: string } {
    return { name: `${channel.guide_number} ${channel.guide_name}` };
  }
}
