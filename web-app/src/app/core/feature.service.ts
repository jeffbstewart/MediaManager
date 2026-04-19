import { Injectable, signal } from '@angular/core';
import { FeatureFlags } from './catalog.service';

/**
 * Holds server-reported feature flags for UI gating.
 * Populated by the home page on load; read by the shell for nav visibility.
 */
@Injectable({ providedIn: 'root' })
export class FeatureService {
  private readonly flags = signal<FeatureFlags>({
    has_personal_videos: false,
    has_cameras: false,
    has_live_tv: false,
    is_admin: false,
    wish_ready_count: 0,
    unmatched_count: 0,
    data_quality_count: 0,
    open_reports_count: 0,
  });

  readonly hasPersonalVideos = () => this.flags().has_personal_videos;
  readonly hasBooks = () => this.flags().has_books ?? false;
  readonly hasMusic = () => this.flags().has_music ?? false;
  readonly hasMusicRadio = () => this.flags().has_music_radio ?? false;
  readonly hasCameras = () => this.flags().has_cameras;
  readonly hasLiveTv = () => this.flags().has_live_tv;
  readonly isAdmin = () => this.flags().is_admin;
  readonly wishReadyCount = () => this.flags().wish_ready_count;
  readonly unmatchedCount = () => this.flags().unmatched_count;
  readonly unmatchedBooksCount = () => this.flags().unmatched_books_count ?? 0;
  readonly unmatchedAudioCount = () => this.flags().unmatched_audio_count ?? 0;
  readonly dataQualityCount = () => this.flags().data_quality_count;
  readonly openReportsCount = () => this.flags().open_reports_count;

  update(flags: FeatureFlags): void {
    this.flags.set(flags);
  }
}
