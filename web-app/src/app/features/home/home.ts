import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AppRoutes } from '../../core/routes';
import { FeatureService } from '../../core/feature.service';
import {
  CatalogService,
  HomeFeed,
  ResumeListeningItem,
} from '../../core/catalog.service';
import { PlaybackQueueService } from '../../core/playback-queue.service';

@Component({
  selector: 'app-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    MatIconModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    DecimalPipe,
  ],
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class HomeComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  private readonly features = inject(FeatureService);
  private readonly playbackQueue = inject(PlaybackQueueService);

  readonly routes = AppRoutes;
  readonly loading = signal(true);
  readonly feed = signal<HomeFeed | null>(null);
  readonly error = signal('');

  async ngOnInit(): Promise<void> {
    try {
      const data = await this.catalog.getHomeFeed();
      this.feed.set(data);
      this.features.update(data.features);
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
