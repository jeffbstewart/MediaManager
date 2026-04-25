import {
  Component, inject, signal, OnInit, OnDestroy, ChangeDetectionStrategy,
  ElementRef, viewChild,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import Hls from 'hls.js';

@Component({
  selector: 'app-live-tv-player',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule],
  template: `
    <div class="player-container" #containerEl tabindex="0"
         (keydown.escape)="goBack()" (keydown.f)="toggleFullscreen()">
      <div class="top-bar" [class.visible]="showControls()">
        <button class="ctrl-btn" (click)="goBack()" aria-label="Back to channel guide">
          <mat-icon>arrow_back</mat-icon>
        </button>
        <span class="channel-label">{{ channelName }}</span>
        <button class="ctrl-btn" (click)="toggleFullscreen()"
                [attr.aria-label]="isFullscreen() ? 'Exit fullscreen' : 'Enter fullscreen'">
          <mat-icon>{{ isFullscreen() ? 'fullscreen_exit' : 'fullscreen' }}</mat-icon>
        </button>
      </div>

      @if (status() !== 'playing') {
        <div class="status-overlay">
          @if (status() === 'error') {
            <mat-icon class="error-icon">error_outline</mat-icon>
            <span class="status-text">{{ statusMessage() }}</span>
            <button class="retry-btn" (click)="startStream()">Retry</button>
          } @else {
            <mat-spinner diameter="40" />
            <span class="status-text">{{ statusMessage() }}</span>
          }
        </div>
      }

      <video #videoEl (click)="toggleControls()" playsinline></video>
    </div>
  `,
  styleUrl: './live-tv-player.scss',
})
export class LiveTvPlayerComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly videoRef = viewChild<ElementRef<HTMLVideoElement>>('videoEl');
  readonly containerRef = viewChild<ElementRef<HTMLDivElement>>('containerEl');

  channelId = 0;
  channelName = '';

  readonly status = signal<'tuning' | 'buffering' | 'playing' | 'error'>('tuning');
  readonly statusMessage = signal('Tuning...');
  readonly showControls = signal(true);
  readonly isFullscreen = signal(false);

  private hls: Hls | null = null;
  private controlsTimer: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    this.channelId = Number(this.route.snapshot.paramMap.get('channelId'));
    this.channelName = this.route.snapshot.queryParamMap.get('name') ?? '';

    if (!this.channelId) {
      this.status.set('error');
      this.statusMessage.set('Invalid channel');
      return;
    }

    this.startStream();
  }

  ngOnDestroy(): void {
    this.destroyHls();
    if (this.controlsTimer) clearTimeout(this.controlsTimer);
  }

  startStream(): void {
    this.destroyHls();
    this.status.set('tuning');
    this.statusMessage.set('Tuning...');

    const streamUrl = `/live-tv-stream/${this.channelId}/stream.m3u8`;

    // Wait for video element to be available
    setTimeout(() => {
      const video = this.videoRef()?.nativeElement;
      if (!video) return;

      if (Hls.isSupported()) {
        const hls = new Hls({
          enableWorker: true,
          lowLatencyMode: true,
        });
        this.hls = hls;

        hls.on(Hls.Events.MANIFEST_PARSED, () => {
          this.status.set('buffering');
          this.statusMessage.set('Buffering...');
          video.play().catch(() => {});
        });

        hls.on(Hls.Events.FRAG_BUFFERED, () => {
          if (this.status() !== 'playing') {
            this.status.set('playing');
            this.resetControlsTimer();
          }
        });

        hls.on(Hls.Events.ERROR, (_event, data) => {
          if (data.fatal) {
            if (data.response?.code === 503) {
              this.status.set('error');
              this.statusMessage.set('All tuners are busy');
            } else if (data.response?.code === 502 || data.response?.code === 504) {
              this.status.set('error');
              this.statusMessage.set('No signal on this channel');
            } else if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
              this.status.set('error');
              this.statusMessage.set('Channel unavailable');
            } else {
              this.status.set('error');
              this.statusMessage.set('Stream error');
            }
            hls.destroy();
          }
        });

        hls.loadSource(streamUrl);
        hls.attachMedia(video);
      } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
        // Safari native HLS
        video.src = streamUrl;
        video.addEventListener('loadedmetadata', () => {
          this.status.set('playing');
          video.play().catch(() => {});
        });
        video.addEventListener('error', () => {
          this.status.set('error');
          this.statusMessage.set('Stream error');
        });
      } else {
        this.status.set('error');
        this.statusMessage.set('HLS playback not supported');
      }
    }, 0);
  }

  goBack(): void {
    this.router.navigate(['/live-tv']);
  }

  toggleFullscreen(): void {
    const container = this.containerRef()?.nativeElement;
    if (!container) return;
    if (document.fullscreenElement) {
      document.exitFullscreen();
      this.isFullscreen.set(false);
    } else {
      container.requestFullscreen();
      this.isFullscreen.set(true);
    }
  }

  toggleControls(): void {
    this.showControls.set(true);
    this.resetControlsTimer();
  }

  private resetControlsTimer(): void {
    if (this.controlsTimer) clearTimeout(this.controlsTimer);
    this.controlsTimer = setTimeout(() => this.showControls.set(false), 3000);
  }

  private destroyHls(): void {
    if (this.hls) {
      this.hls.destroy();
      this.hls = null;
    }
  }
}
