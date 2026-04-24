import {
  ChangeDetectionStrategy, Component, ElementRef, HostListener, effect, inject, signal, viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { PlaybackQueueService } from '../../core/playback-queue.service';
import { CatalogService } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';
import { QueuePanelComponent } from './queue-panel';

/**
 * Persistent bottom-bar audio player. Lives in the shell so it stays
 * mounted across in-app navigation — the `<audio>` element isn't rebuilt
 * when the router swaps feature components.
 *
 * Wiring:
 *   - `src` binds to [queue.currentTrackUrl]; swapping tracks triggers
 *     the element's canplay cycle, and the effect below calls play().
 *   - `timeupdate` feeds [queue.reportPosition] which rate-limits
 *     server-side progress reports.
 *   - `ended` advances via [queue.next].
 *   - An effect watches [queue.playing] / [queue.positionSeconds] and
 *     drives play / pause / seek on the element.
 */
@Component({
  selector: 'app-audio-player',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatButtonModule, QueuePanelComponent],
  templateUrl: './audio-player.html',
  styleUrl: './audio-player.scss',
})
export class AudioPlayerComponent {
  readonly queue = inject(PlaybackQueueService);
  private readonly catalog = inject(CatalogService);
  readonly routes = AppRoutes;

  readonly audioEl = viewChild<ElementRef<HTMLAudioElement>>('audioEl');

  /** Drop-up queue-panel visibility. Closed by default. */
  readonly queuePanelOpen = signal<boolean>(false);

  /**
   * Cached public-art tokens keyed by album title id. Tokens are good
   * for 12 h, so a typical listening session never needs a refresh —
   * we mint once per album encountered and reuse for every track on it.
   */
  private readonly artTokenCache = new Map<number, string>();

  /** Throttle for `setPositionState` calls — once per second is plenty. */
  private lastPositionStateAt = 0;

  toggleQueuePanel(): void {
    this.queuePanelOpen.update(v => !v);
  }

  /**
   * Stop playback and dismiss the player. The bar auto-hides once
   * the queue is empty, so clearQueue() is what makes the "×" work.
   */
  closePlayer(): void {
    this.queue.clearQueue();
    this.queuePanelOpen.set(false);
  }

  /** Formatted "m:ss" for the position / duration display. */
  formatTime(seconds: number): string {
    if (!isFinite(seconds) || seconds <= 0) return '0:00';
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  }

  constructor() {
    // Source sync runs FIRST so that when a new track starts playing, the
    // src change + load() happen before the play/pause effect tries to
    // call play(). Otherwise play() is aborted by the subsequent load(),
    // producing an AbortError and a stuck player.
    effect(() => {
      const el = this.audioEl()?.nativeElement;
      if (!el) return;
      const url = this.queue.currentTrackUrl();
      if (!url) { el.removeAttribute('src'); el.load(); return; }
      if (el.getAttribute('src') !== url) {
        el.src = url;
        el.load();
      }
    });

    // Play / pause sync. Only call play() when the element has enough data
    // queued (readyState >= HAVE_CURRENT_DATA); if it's still loading,
    // let the `canplay` handler pick up the intent. Prevents the
    // "play() request was interrupted by a new load" AbortError.
    effect(() => {
      const el = this.audioEl()?.nativeElement;
      if (!el) return;
      if (this.queue.playing() && el.paused) {
        if (el.readyState >= 2) {
          el.play().catch(err => {
            console.warn('[audio] play() rejected:', err?.name, err?.message, 'readyState=', el.readyState);
          });
        }
        // Otherwise wait — onCanPlay() will call play() below.
      } else if (!this.queue.playing() && !el.paused) {
        el.pause();
      }
    });

    // Volume / mute effect.
    effect(() => {
      const el = this.audioEl()?.nativeElement;
      if (!el) return;
      el.volume = this.queue.volume();
      el.muted = this.queue.muted();
    });

    // Seek effect — only applies large jumps (scrubber clicks); the
    // timeupdate-driven positionSeconds updates would cause a feedback
    // loop if we seeked on every tick, so we tolerate small deltas.
    effect(() => {
      const el = this.audioEl()?.nativeElement;
      if (!el) return;
      const target = this.queue.positionSeconds();
      if (!isFinite(el.currentTime)) return;
      if (Math.abs(el.currentTime - target) > 1.5) {
        try { el.currentTime = target; } catch { /* seek failed — try again on next tick */ }
      }
    });

    this.setupMediaSession();
  }

  /**
   * Wire up the MediaSession API so the OS lock-screen / Control Center
   * can show now-playing metadata and forward transport-control taps.
   * Only runs in browsers that implement the API (all modern desktop
   * browsers + WebKit on iOS 15+).
   *
   * Action handlers are registered once. Metadata + playback state are
   * driven by signal effects so they track the queue automatically.
   */
  private setupMediaSession(): void {
    if (!('mediaSession' in navigator)) return;
    const ms = navigator.mediaSession;

    ms.setActionHandler('play', () => this.queue.resume());
    ms.setActionHandler('pause', () => this.queue.pause());
    ms.setActionHandler('previoustrack', () => this.queue.prev());
    ms.setActionHandler('nexttrack', () => this.queue.next());
    ms.setActionHandler('seekto', (event) => {
      if (typeof event.seekTime === 'number') this.queue.seek(event.seekTime);
    });

    // Metadata effect — runs whenever the current track changes. Mints
    // a public-art token (cached per album) so the lock-screen artwork
    // fetch — which doesn't share the browser's auth — can succeed.
    effect(() => {
      const t = this.queue.currentTrack();
      if (!t) {
        ms.metadata = null;
        ms.playbackState = 'none';
        return;
      }
      void this.applyMetadata(t.trackName, t.albumName, t.primaryArtistName, t.albumTitleId);
    });

    // Playback-state effect.
    effect(() => {
      ms.playbackState = this.queue.playing() ? 'playing' : (this.queue.currentTrack() ? 'paused' : 'none');
    });
  }

  private async applyMetadata(
    title: string,
    album: string | null,
    artist: string | null,
    albumTitleId: number | null,
  ): Promise<void> {
    const ms = navigator.mediaSession;
    let artwork: MediaImage[] = [];
    if (albumTitleId != null) {
      try {
        let token = this.artTokenCache.get(albumTitleId);
        if (!token) {
          const res = await this.catalog.getPublicArtToken(albumTitleId);
          token = res.token;
          this.artTokenCache.set(albumTitleId, token);
        }
        const url = `/public/album-art/${token}`;
        // 500x500 is the FULL size that PublicAlbumArtHttpService serves.
        artwork = [{ src: url, sizes: '500x500', type: 'image/jpeg' }];
      } catch {
        // Token mint failed — fall through with empty artwork. The lock
        // screen will show a generic icon but title/artist/album still render.
      }
    }
    ms.metadata = new MediaMetadata({
      title,
      artist: artist ?? '',
      album: album ?? '',
      artwork,
    });
  }

  onTimeUpdate(): void {
    const el = this.audioEl()?.nativeElement;
    if (!el) return;
    this.queue.reportPosition(el.currentTime, isFinite(el.duration) ? el.duration : 0);
    this.updateMediaSessionPosition(el);
  }

  /**
   * Push position to MediaSession's setPositionState so the lock-screen
   * scrubber tracks the audio. Throttled to 1 Hz — `timeupdate` fires
   * at ~4 Hz and the lock screen doesn't need that resolution.
   */
  private updateMediaSessionPosition(el: HTMLAudioElement): void {
    if (!('mediaSession' in navigator)) return;
    const now = Date.now();
    if (now - this.lastPositionStateAt < 1000) return;
    this.lastPositionStateAt = now;
    if (!isFinite(el.duration) || el.duration <= 0) return;
    try {
      navigator.mediaSession.setPositionState({
        duration: el.duration,
        position: Math.min(el.currentTime, el.duration),
        playbackRate: el.playbackRate || 1,
      });
    } catch {
      // setPositionState throws if duration < position or other invariants
      // fail; nothing actionable, just skip this tick.
    }
  }

  onEnded(): void {
    // Natural end-of-track is not a skip — recordDepartingTrack inside
    // next() inspects position vs. duration so the distinction is picked
    // up automatically.
    this.queue.flushProgress();
    this.queue.next();
  }

  onLoadedMetadata(): void {
    const el = this.audioEl()?.nativeElement;
    if (el && isFinite(el.duration)) {
      this.queue.reportPosition(el.currentTime, el.duration);
    }
  }

  onScrub(event: Event): void {
    const value = parseFloat((event.target as HTMLInputElement).value);
    if (!isNaN(value)) this.queue.seek(value);
  }

  /** Volume slider: setting a non-zero value also un-mutes automatically. */
  onVolume(event: Event): void {
    const value = parseFloat((event.target as HTMLInputElement).value);
    if (isNaN(value)) return;
    this.queue.setVolume(value);
    if (value > 0 && this.queue.muted()) this.queue.toggleMute();
    if (value === 0 && !this.queue.muted()) this.queue.toggleMute();
  }

  /** Pick a volume icon that reflects the current level / mute state. */
  volumeIcon(): string {
    if (this.queue.muted() || this.queue.volume() === 0) return 'volume_off';
    if (this.queue.volume() < 0.5) return 'volume_down';
    return 'volume_up';
  }

  onError(): void {
    const el = this.audioEl()?.nativeElement;
    const err = el?.error;
    console.error('[audio] element error:', {
      code: err?.code,
      message: err?.message,
      src: el?.currentSrc,
      networkState: el?.networkState,
      readyState: el?.readyState,
    });
  }

  onStalled(): void {
    const el = this.audioEl()?.nativeElement;
    console.warn('[audio] stalled:', {
      src: el?.currentSrc,
      networkState: el?.networkState,
      readyState: el?.readyState,
      buffered: el ? Array.from({ length: el.buffered.length }, (_, i) =>
        `${el.buffered.start(i)}-${el.buffered.end(i)}`).join(',') : '',
    });
  }

  onCanPlay(): void {
    const el = this.audioEl()?.nativeElement;
    console.info('[audio] canplay:', {
      src: el?.currentSrc,
      duration: el?.duration,
      readyState: el?.readyState,
    });
    // Pick up deferred play-intent: if the service wants us playing but
    // the effect above skipped play() because data wasn't ready, try now.
    if (el && this.queue.playing() && el.paused) {
      el.play().catch(err => {
        console.warn('[audio] play() rejected on canplay:', err?.name, err?.message);
      });
    }
  }

  @HostListener('window:beforeunload')
  onWindowClose(): void {
    // Best-effort final progress flush — browsers don't guarantee async
    // network during unload, but a synchronous sendBeacon-style fire
    // would need a separate endpoint. The 10 s interval usually captures
    // the last meaningful position anyway.
    this.queue.flushProgress();
  }
}
