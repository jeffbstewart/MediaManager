import {
  ChangeDetectionStrategy, Component, ElementRef, HostListener, effect, inject, viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { PlaybackQueueService } from '../../core/playback-queue.service';
import { AppRoutes } from '../../core/routes';

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
  imports: [RouterLink, MatIconModule, MatButtonModule],
  templateUrl: './audio-player.html',
  styleUrl: './audio-player.scss',
})
export class AudioPlayerComponent {
  readonly queue = inject(PlaybackQueueService);
  readonly routes = AppRoutes;

  readonly audioEl = viewChild<ElementRef<HTMLAudioElement>>('audioEl');

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
  }

  onTimeUpdate(): void {
    const el = this.audioEl()?.nativeElement;
    if (!el) return;
    this.queue.reportPosition(el.currentTime, isFinite(el.duration) ? el.duration : 0);
  }

  onEnded(): void {
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
