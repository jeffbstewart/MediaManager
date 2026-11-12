import { Component, computed, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AppRoutes } from '../../core/routes';
import { FeatureService } from '../../core/feature.service';
import { AccountService } from '../../core/account.service';
import { HOME_FAQ, HOME_FAQ_LIVE_TV } from './home-faq';
import {
  CatalogService,
  HomeFeed,
  MissingSeason,
  ResumeListeningItem,
  TvChannelInfo,
} from '../../core/catalog.service';
import { PlaybackQueueService } from '../../core/playback-queue.service';
import {
  ChipLink,
  ChipRowComponent,
  FaqAccordionComponent,
  HeroBannerComponent,
  HeroCarouselComponent,
  MediaRowComponent,
  PersonCardComponent,
  PosterCardComponent,
  SectionHeadComponent,
  WideCardComponent,
} from '../../shared/media';

@Component({
  selector: 'app-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatIconModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    DecimalPipe,
    ChipRowComponent,
    FaqAccordionComponent,
    HeroBannerComponent,
    HeroCarouselComponent,
    MediaRowComponent,
    PersonCardComponent,
    PosterCardComponent,
    SectionHeadComponent,
    WideCardComponent,
    RouterLink,
  ],
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class HomeComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  readonly features = inject(FeatureService);
  private readonly playbackQueue = inject(PlaybackQueueService);
  private readonly accounts = inject(AccountService);

  readonly routes = AppRoutes;
  readonly loading = signal(true);
  readonly feed = signal<HomeFeed | null>(null);
  readonly error = signal('');
  /** FAQ rows (spec 3.9); the live TV question only when live TV is configured. */
  readonly faq = computed(() => this.features.hasLiveTv() ? [...HOME_FAQ, HOME_FAQ_LIVE_TV] : HOME_FAQ);

  /** First channels for the On Live TV row; empty when live TV is off or the list fails. */
  readonly liveChannels = signal<TvChannelInfo[]>([]);

  /** "Welcome back, {username}", or just "Welcome back" until the account is known. */
  readonly welcomeHeading = computed(() => {
    const username = this.accounts.account()?.username;
    return username ? `Welcome back, ${username}` : 'Welcome back';
  });

  /**
   * Browse Your Library chips (spec 3.5): the library sections the nav
   * shows, in the prototype's order, under the same feature flags.
   */
  readonly browseChips = computed<ChipLink[]>(() => {
    const f = this.features;
    const chips: (ChipLink | null)[] = [
      { label: 'Movies', icon: 'movie', link: AppRoutes.movies() },
      { label: 'TV Shows', icon: 'tv', link: AppRoutes.tvShows() },
      f.hasBooks() ? { label: 'Books', icon: 'auto_stories', link: AppRoutes.books() } : null,
      f.hasMusic() ? { label: 'Music', icon: 'album', link: AppRoutes.music() } : null,
      { label: 'Collections', icon: 'collections_bookmark', link: AppRoutes.collections() },
      f.hasPersonalVideos() ? { label: 'Family', icon: 'family_restroom', link: AppRoutes.personalVideos() } : null,
      f.hasLiveTv() ? { label: 'Live TV', icon: 'live_tv', link: AppRoutes.liveTv() } : null,
      f.hasCameras() ? { label: 'Cameras', icon: 'videocam', link: AppRoutes.cameras() } : null,
      f.hasMusic() ? { label: 'Playlists', icon: 'queue_music', link: AppRoutes.playlists() } : null,
      { label: 'Tags', icon: 'label', link: AppRoutes.tags() },
      { label: 'My Wish List', icon: 'favorite', link: AppRoutes.wishlist() },
    ];
    return chips.filter((c): c is ChipLink => c !== null);
  });

  /** Up to 28 distinct recent posters for the welcome mosaic. */
  readonly mosaic = computed(() => {
    const feed = this.feed();
    if (!feed) return [] as string[];
    const seen = new Set<number>();
    const posters: string[] = [];
    for (const t of [...feed.recently_added, ...feed.recently_watched, ...feed.missing_seasons]) {
      if (!t.poster_url || seen.has(t.title_id)) continue;
      seen.add(t.title_id);
      posters.push(t.poster_url);
      if (posters.length === 28) break;
    }
    return posters;
  });

  async ngOnInit(): Promise<void> {
    void this.accounts.load();
    try {
      const data = await this.catalog.getHomeFeed();
      this.feed.set(data);
      this.features.update(data.features);
      if (this.features.hasLiveTv()) void this.loadLiveChannels();
    } catch {
      this.error.set('Failed to load home feed');
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Starts a single-track queue from the Continue Listening carousel.
   * The bottom-bar player resumes at the saved position via the effect
   * that seeks when positionSeconds > element currentTime + 1.5s —
   * seeding the queue's positionSeconds signal kicks the seek.
   */
  resumeListening(item: ResumeListeningItem): void {
    this.playbackQueue.playSingleTrack({
      trackId: item.track_id,
      trackName: item.track_name,
      durationSeconds: item.duration_seconds > 0 ? item.duration_seconds : null,
      albumTitleId: item.title_id,
      albumName: item.title_name,
      albumPosterUrl: item.poster_url,
      primaryArtistName: item.artist_name,
    });
    // Seed the saved position so the player picks up where we left off.
    // The audio element's canplay + seek effect handles the mechanics.
    this.playbackQueue.seek(item.position_seconds);
  }

  /** "S2, S3" for a New Seasons card. */
  seasonList(item: MissingSeason): string {
    return item.missing_seasons.map(s => `S${s.season_number}`).join(', ');
  }

  /** "New S4": the first missing season, for the card's corner badge. */
  newSeasonBadge(item: MissingSeason): string | null {
    const first = item.missing_seasons[0];
    return first ? `New S${first.season_number}` : null;
  }

  /** The On Live TV row shows the first ten channels; a failed list just hides the row. */
  private async loadLiveChannels(): Promise<void> {
    try {
      const list = await this.catalog.getTvChannels();
      this.liveChannels.set(list.channels.slice(0, 10));
    } catch {
      this.liveChannels.set([]);
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
