import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/**
 * One row in the audio player's queue. `albumPosterUrl` is used for the
 * bottom-bar thumbnail; `albumTitleId` lets the now-playing chip link back
 * to the AlbumScreen the track came from.
 */
export interface QueuedTrack {
  trackId: number;
  trackName: string;
  durationSeconds: number | null;
  albumTitleId: number;
  albumName: string;
  albumPosterUrl: string | null;
  primaryArtistName: string | null;
  /** Optional per-track artist override, e.g. on compilations. */
  trackArtistName?: string | null;
}

export type RepeatMode = 'OFF' | 'ONE' | 'ALL';

/**
 * Owns the web audio player's queue, current-track, position, and shuffle /
 * repeat state. The [AudioPlayerComponent] in the shell binds an HTML5
 * `<audio>` element to [currentTrackUrl] and reports playback events back
 * through [reportPosition]. Every ~10 s of play the service POSTs to
 * /api/v2/audio/progress so "Continue Listening" has a resume point.
 *
 * Persistence across route changes is M5-deferred (see docs/MUSIC.md): the
 * audio element lives inside the shell, so in-app navigation does keep it
 * alive, but a full-reload or tab close ends playback. The server-side
 * listening_progress row is the safety net — the Continue Listening
 * carousel is one click away.
 */
@Injectable({ providedIn: 'root' })
export class PlaybackQueueService {
  private readonly http = inject(HttpClient);

  /** Upstream queue; index 0 is up next after the current track finishes. */
  private readonly queue = signal<QueuedTrack[]>([]);
  private readonly currentIndex = signal<number>(-1);
  readonly playing = signal<boolean>(false);
  readonly positionSeconds = signal<number>(0);
  readonly durationSeconds = signal<number>(0);
  readonly volume = signal<number>(1.0);
  readonly muted = signal<boolean>(false);
  readonly shuffleEnabled = signal<boolean>(false);
  readonly repeatMode = signal<RepeatMode>('OFF');

  readonly currentTrack = computed<QueuedTrack | null>(() => {
    const idx = this.currentIndex();
    const q = this.queue();
    return idx >= 0 && idx < q.length ? q[idx] : null;
  });

  readonly hasQueue = computed<boolean>(() => this.currentTrack() !== null);

  readonly currentTrackUrl = computed<string | null>(() => {
    const t = this.currentTrack();
    return t ? `/audio/${t.trackId}` : null;
  });

  private lastReportedAt = 0;
  private readonly REPORT_INTERVAL_MS = 10_000;

  /** Start playing an album from [startIndex] of its track list. */
  playAlbum(tracks: QueuedTrack[], startIndex: number = 0): void {
    if (tracks.length === 0) return;
    this.queue.set([...tracks]);
    this.currentIndex.set(Math.max(0, Math.min(startIndex, tracks.length - 1)));
    this.playing.set(true);
    this.positionSeconds.set(0);
    // Duration comes from the audio element's metadata-loaded event; seed
    // with the catalogue value so the scrubber can render before metadata
    // arrives (e.g. while FFmpeg is still transcoding).
    const dur = this.currentTrack()?.durationSeconds ?? 0;
    this.durationSeconds.set(dur);
  }

  /** Queue a single track and start it; useful from the home carousel. */
  playSingleTrack(track: QueuedTrack): void {
    this.playAlbum([track], 0);
  }

  /** Advance to the next queued track, respecting shuffle / repeat. */
  next(): void {
    const q = this.queue();
    if (q.length === 0) return;
    const repeat = this.repeatMode();
    if (repeat === 'ONE') {
      // Restart the current track; audio element will seek to 0.
      this.positionSeconds.set(0);
      return;
    }

    let nextIdx: number;
    if (this.shuffleEnabled()) {
      nextIdx = this.pickShuffledNext();
    } else {
      nextIdx = this.currentIndex() + 1;
    }

    if (nextIdx >= q.length) {
      if (repeat === 'ALL') {
        nextIdx = 0;
      } else {
        this.playing.set(false);
        this.currentIndex.set(-1);
        return;
      }
    }
    this.currentIndex.set(nextIdx);
    this.positionSeconds.set(0);
    this.playing.set(true);
  }

  prev(): void {
    // Standard media-app convention: first 3 s seeks to 0, after that goes
    // to previous track.
    if (this.positionSeconds() > 3) {
      this.positionSeconds.set(0);
      return;
    }
    const idx = this.currentIndex();
    if (idx <= 0) {
      this.positionSeconds.set(0);
      return;
    }
    this.currentIndex.set(idx - 1);
    this.positionSeconds.set(0);
    this.playing.set(true);
  }

  pause(): void {
    this.playing.set(false);
  }

  resume(): void {
    if (this.currentTrack() != null) this.playing.set(true);
  }

  toggle(): void {
    if (this.playing()) this.pause(); else this.resume();
  }

  seek(seconds: number): void {
    const clamped = Math.max(0, Math.min(seconds, this.durationSeconds()));
    this.positionSeconds.set(clamped);
  }

  setVolume(v: number): void {
    this.volume.set(Math.max(0, Math.min(1, v)));
  }

  toggleMute(): void {
    this.muted.set(!this.muted());
  }

  toggleShuffle(): void {
    this.shuffleEnabled.set(!this.shuffleEnabled());
  }

  cycleRepeat(): void {
    const next: RepeatMode = this.repeatMode() === 'OFF' ? 'ALL'
      : this.repeatMode() === 'ALL' ? 'ONE' : 'OFF';
    this.repeatMode.set(next);
  }

  /**
   * Bound to the audio element's `timeupdate` event. Rate-limits server-side
   * progress reports to the configured interval.
   */
  reportPosition(position: number, duration: number): void {
    this.positionSeconds.set(position);
    if (duration > 0) this.durationSeconds.set(duration);

    const now = Date.now();
    if (now - this.lastReportedAt < this.REPORT_INTERVAL_MS) return;
    this.lastReportedAt = now;
    const track = this.currentTrack();
    if (!track) return;
    this.postProgress(track.trackId, Math.floor(position), Math.floor(duration));
  }

  /** Called on pause / track-end / close to flush a final progress snapshot. */
  flushProgress(): void {
    const track = this.currentTrack();
    if (!track) return;
    this.postProgress(track.trackId, Math.floor(this.positionSeconds()), Math.floor(this.durationSeconds()));
  }

  private pickShuffledNext(): number {
    const q = this.queue();
    if (q.length <= 1) return 0;
    // Simple uniform shuffle: pick any index that isn't the current one.
    let idx: number;
    do {
      idx = Math.floor(Math.random() * q.length);
    } while (idx === this.currentIndex());
    return idx;
  }

  private postProgress(trackId: number, position: number, duration: number): void {
    // Fire-and-forget; we don't want progress posts to block playback.
    firstValueFrom(
      this.http.post('/api/v2/audio/progress', {
        track_id: trackId,
        position_seconds: position,
        duration_seconds: duration > 0 ? duration : null,
      }),
    ).catch(() => {
      /* swallow — the next tick will retry */
    });
  }
}
