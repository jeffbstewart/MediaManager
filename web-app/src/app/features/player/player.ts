import {
  Component, inject, signal, OnInit, OnDestroy, ChangeDetectionStrategy,
  ElementRef, viewChild,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

interface ChapterData {
  chapters: { number: number; start: number; end: number; title: string }[];
  skipSegments: { type: string; start: number; end: number; method: string }[];
}

interface ThumbCue {
  start: number;
  end: number;
  url: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

@Component({
  selector: 'app-player',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatButtonModule, MatProgressSpinnerModule],
  templateUrl: './player.html',
  styleUrl: './player.scss',
})
export class PlayerComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly http = inject(HttpClient);

  readonly videoRef = viewChild<ElementRef<HTMLVideoElement>>('videoEl');
  readonly containerRef = viewChild<ElementRef<HTMLDivElement>>('containerEl');
  readonly thumbRef = viewChild<ElementRef<HTMLDivElement>>('thumbEl');
  readonly thumbImgRef = viewChild<ElementRef<HTMLImageElement>>('thumbImg');

  transcodeId = 0;
  titleName = '';

  // State signals
  readonly videoSrc = signal('');
  readonly loading = signal(true);
  readonly loadingText = signal('Starting playback...');
  readonly paused = signal(true);
  readonly showControls = signal(true);
  readonly currentTime = signal(0);
  readonly duration = signal(0);
  readonly buffered = signal(0);
  readonly isFullscreen = signal(false);

  // Subtitles
  readonly subsAvailable = signal(false);
  readonly subsEnabled = signal(true);

  // Chapters & skip segments
  chapters: ChapterData = { chapters: [], skipSegments: [] };
  readonly showSkipIntro = signal(false);
  readonly showUpNext = signal(false);
  readonly upNextCountdown = signal(10);
  private upNextTimerId: ReturnType<typeof setInterval> | null = null;
  private introSegment: { start: number; end: number } | null = null;
  private creditsSegment: { start: number; end: number } | null = null;
  nextEpisodeLabel = '';
  nextTranscodeId: number | null = null;

  // Thumbnails
  thumbCues: ThumbCue[] = [];
  readonly showThumb = signal(false);

  // Resume
  readonly showResume = signal(false);
  resumePosition = 0;

  // Progress tracking
  private progressInterval: ReturnType<typeof setInterval> | null = null;
  private controlsTimer: ReturnType<typeof setTimeout> | null = null;
  private lastReportedTime = 0;


  async ngOnInit(): Promise<void> {
    this.transcodeId = Number(this.route.snapshot.paramMap.get('transcodeId'));
    this.titleName = this.route.snapshot.queryParamMap.get('title') ?? '';
    const subsParam = this.route.snapshot.queryParamMap.get('subs');
    if (subsParam === 'off') this.subsEnabled.set(false);

    if (!this.transcodeId) {
      this.loadingText.set('Invalid transcode ID');
      return;
    }

    // Fetch metadata in parallel
    const [chaptersResult, thumbsResult, subsResult, progressResult, nextEpResult] = await Promise.allSettled([
      firstValueFrom(this.http.get<ChapterData>(`/stream/${this.transcodeId}/chapters.json`)),
      firstValueFrom(this.http.get(`/stream/${this.transcodeId}/thumbs.vtt`, { responseType: 'text' })),
      firstValueFrom(this.http.head(`/stream/${this.transcodeId}/subs.vtt`, { observe: 'response' })),
      firstValueFrom(this.http.get<{ position: number; duration: number }>(`/playback-progress/${this.transcodeId}`)),
      firstValueFrom(this.http.get<{ transcodeId: number; label: string }>(`/stream/${this.transcodeId}/next-episode`)),
    ]);

    if (chaptersResult.status === 'fulfilled') {
      this.chapters = chaptersResult.value;
      this.introSegment = this.chapters.skipSegments.find(s => s.type === 'INTRO') ?? null;
      this.creditsSegment = this.chapters.skipSegments.find(s => s.type === 'END_CREDITS') ?? null;
    }

    if (thumbsResult.status === 'fulfilled') {
      this.thumbCues = this.parseThumbsVtt(thumbsResult.value);
    }

    if (subsResult.status === 'fulfilled' && subsResult.value.ok) {
      this.subsAvailable.set(true);
    }

    if (nextEpResult.status === 'fulfilled') {
      this.nextTranscodeId = nextEpResult.value.transcodeId;
      this.nextEpisodeLabel = nextEpResult.value.label;
    }

    if (progressResult.status === 'fulfilled' && progressResult.value.position > 10) {
      this.resumePosition = progressResult.value.position;
      this.showResume.set(true);
      this.loading.set(false);
      return; // Wait for user to choose resume/start over — video src not set yet
    }

    this.startPlayback();
    this.loading.set(false);
  }

  ngOnDestroy(): void {
    this.reportProgress(true);
    if (this.progressInterval) clearInterval(this.progressInterval);
    if (this.controlsTimer) clearTimeout(this.controlsTimer);
    if (this.upNextTimerId) clearInterval(this.upNextTimerId);
  }

  // --- Resume prompt ---

  onResume(): void {
    this.showResume.set(false);
    this.startPlayback();
    const video = this.videoRef()?.nativeElement;
    if (video) {
      video.addEventListener('loadedmetadata', () => {
        video.currentTime = this.resumePosition;
        video.play();
      }, { once: true });
    }
  }

  onStartOver(): void {
    this.showResume.set(false);
    this.reportClear();
    this.startPlayback();
    const video = this.videoRef()?.nativeElement;
    if (video) {
      video.addEventListener('canplay', () => video.play(), { once: true });
    }
  }

  private startPlayback(): void {
    this.videoSrc.set(`/stream/${this.transcodeId}`);
    // Video element renders after signal update; set up listeners after render
    setTimeout(() => this.setupVideoListeners(), 0);
  }

  // --- Video event listeners ---

  private setupVideoListeners(): void {
    const video = this.videoRef()?.nativeElement;
    if (!video) return;

    video.addEventListener('loadstart', () => {
      this.loading.set(true);
      this.loadingText.set('Starting playback...');
    });
    video.addEventListener('waiting', () => {
      this.loading.set(true);
      this.loadingText.set('Buffering...');
    });
    video.addEventListener('canplay', () => this.loading.set(false));
    video.addEventListener('playing', () => {
      this.loading.set(false);
      this.paused.set(false);
      this.resetControlsTimer();
    });
    video.addEventListener('pause', () => {
      this.paused.set(true);
      this.showControls.set(true);
      this.reportProgress(false);
    });
    video.addEventListener('ended', () => {
      this.paused.set(true);
      this.showControls.set(true);
      if (this.nextTranscodeId && !this.showUpNext()) {
        this.startUpNextCountdown();
      }
    });
    video.addEventListener('loadedmetadata', () => {
      this.duration.set(video.duration);
    });
    video.addEventListener('timeupdate', () => {
      this.currentTime.set(video.currentTime);
      this.updateBuffered(video);
      this.checkSkipSegments(video.currentTime);
    });
    // If the video already loaded before listeners were attached, sync state now
    if (video.readyState >= 3) {
      this.loading.set(false);
      this.duration.set(video.duration);
    }

    video.addEventListener('error', () => {
      this.loading.set(true);
      this.loadingText.set('Playback error');
    });

    // Start progress reporting
    this.progressInterval = setInterval(() => this.reportProgress(false), 60000);

    // Add subtitle track if available
    if (this.subsAvailable()) {
      const track = document.createElement('track');
      track.kind = 'subtitles';
      track.srclang = 'en';
      track.label = 'English';
      track.src = `/stream/${this.transcodeId}/subs.vtt`;
      track.default = this.subsEnabled();
      video.appendChild(track);
      if (video.textTracks[0]) {
        video.textTracks[0].mode = this.subsEnabled() ? 'showing' : 'hidden';
      }
    }
  }

  // --- Playback controls ---

  togglePlay(): void {
    const video = this.videoRef()?.nativeElement;
    if (!video) return;
    if (video.paused) {
      video.play();
    } else {
      video.pause();
    }
  }

  seek(event: MouseEvent): void {
    const video = this.videoRef()?.nativeElement;
    const bar = event.currentTarget as HTMLElement;
    if (!video || !bar) return;
    const rect = bar.getBoundingClientRect();
    const fraction = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
    video.currentTime = fraction * video.duration;
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

  toggleSubs(): void {
    const video = this.videoRef()?.nativeElement;
    if (!video?.textTracks[0]) return;
    const enabled = !this.subsEnabled();
    this.subsEnabled.set(enabled);
    video.textTracks[0].mode = enabled ? 'showing' : 'hidden';
  }

  skipIntro(): void {
    const video = this.videoRef()?.nativeElement;
    if (!video || !this.introSegment) return;
    video.currentTime = this.introSegment.end;
    this.showSkipIntro.set(false);
  }

  skipBack(): void {
    const video = this.videoRef()?.nativeElement;
    if (video) video.currentTime = Math.max(0, video.currentTime - 10);
  }

  skipForward(): void {
    const video = this.videoRef()?.nativeElement;
    if (video) video.currentTime = Math.min(video.duration, video.currentTime + 30);
  }

  goBack(): void {
    window.history.back();
  }

  // --- Up Next ---

  playNext(): void {
    if (!this.nextTranscodeId) return;
    this.reportProgress(true);
    if (this.upNextTimerId) clearInterval(this.upNextTimerId);
    this.router.navigate(['/play', this.nextTranscodeId], {
      queryParams: { title: this.titleName, subs: this.subsEnabled() ? 'on' : 'off' },
    });
  }

  cancelUpNext(): void {
    this.showUpNext.set(false);
    if (this.upNextTimerId) {
      clearInterval(this.upNextTimerId);
      this.upNextTimerId = null;
    }
  }

  private startUpNextCountdown(): void {
    this.showUpNext.set(true);
    this.upNextCountdown.set(10);
    this.upNextTimerId = setInterval(() => {
      const n = this.upNextCountdown() - 1;
      this.upNextCountdown.set(n);
      if (n <= 0) this.playNext();
    }, 1000);
  }

  // --- Controls visibility ---

  onMouseMove(): void {
    this.showControls.set(true);
    this.resetControlsTimer();
  }

  onMouseLeave(): void {
    if (!this.paused()) {
      this.showControls.set(false);
    }
  }

  private resetControlsTimer(): void {
    if (this.controlsTimer) clearTimeout(this.controlsTimer);
    if (!this.paused()) {
      this.controlsTimer = setTimeout(() => this.showControls.set(false), 3000);
    }
  }

  // --- Seek bar helpers ---

  get progressPercent(): number {
    const d = this.duration();
    return d > 0 ? (this.currentTime() / d) * 100 : 0;
  }

  get bufferedPercent(): number {
    const d = this.duration();
    return d > 0 ? (this.buffered() / d) * 100 : 0;
  }

  formatTime(seconds: number): string {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    const mm = m.toString().padStart(2, '0');
    const ss = s.toString().padStart(2, '0');
    return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`;
  }

  formatResume(seconds: number): string {
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  }

  chapterPercent(startSeconds: number): number {
    const d = this.duration();
    return d > 0 ? (startSeconds / d) * 100 : 0;
  }

  // --- Thumbnail preview ---

  onSeekBarHover(event: MouseEvent): void {
    if (this.thumbCues.length === 0) {
      this.showThumb.set(false);
      return;
    }
    const bar = event.currentTarget as HTMLElement;
    const rect = bar.getBoundingClientRect();
    const fraction = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
    const time = fraction * this.duration();
    const cue = this.thumbCues.find(c => time >= c.start && time < c.end);
    if (!cue) {
      this.showThumb.set(false);
      return;
    }
    const left = Math.max(80, Math.min(rect.width - 80, event.clientX - rect.left));
    const container = this.thumbRef()?.nativeElement;
    const img = this.thumbImgRef()?.nativeElement;
    if (container && img) {
      container.style.left = `${left}px`;
      img.src = `/stream/${this.transcodeId}/${cue.url}`;
      img.style.left = `-${cue.x}px`;
      img.style.top = `-${cue.y}px`;
    }
    this.showThumb.set(true);
  }

  onSeekBarLeave(): void {
    this.showThumb.set(false);
  }

  // --- Skip segment detection ---

  private checkSkipSegments(time: number): void {
    if (this.introSegment) {
      this.showSkipIntro.set(time >= this.introSegment.start && time < this.introSegment.end);
    }
    if (this.creditsSegment && time >= this.creditsSegment.start && this.nextTranscodeId && !this.showUpNext()) {
      this.startUpNextCountdown();
    }
  }

  // --- Progress tracking ---

  private updateBuffered(video: HTMLVideoElement): void {
    if (video.buffered.length > 0) {
      this.buffered.set(video.buffered.end(video.buffered.length - 1));
    }
  }

  private reportProgress(isFinal: boolean): void {
    const video = this.videoRef()?.nativeElement;
    if (!video || !video.duration || video.currentTime < 1) return;
    if (Math.abs(video.currentTime - this.lastReportedTime) < 5 && !isFinal) return;
    this.lastReportedTime = video.currentTime;
    const body = JSON.stringify({ position: video.currentTime, duration: video.duration });
    if (isFinal) {
      navigator.sendBeacon(`/playback-progress/${this.transcodeId}`, body);
    } else {
      this.http.post(`/playback-progress/${this.transcodeId}`, { position: video.currentTime, duration: video.duration })
        .subscribe();
    }
  }

  private reportClear(): void {
    this.http.delete(`/playback-progress/${this.transcodeId}`).subscribe();
  }

  // --- VTT parsing ---

  private parseThumbsVtt(vtt: string): ThumbCue[] {
    const cues: ThumbCue[] = [];
    const lines = vtt.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();
      if (!line.includes(' --> ')) continue;
      const [startStr, endStr] = line.split(' --> ');
      const start = this.parseVttTime(startStr);
      const end = this.parseVttTime(endStr);
      const next = lines[i + 1]?.trim();
      if (!next) continue;
      const match = next.match(/^(.+)#xywh=(\d+),(\d+),(\d+),(\d+)$/);
      if (!match) continue;
      cues.push({
        start, end,
        url: match[1],
        x: Number(match[2]),
        y: Number(match[3]),
        w: Number(match[4]),
        h: Number(match[5]),
      });
    }
    return cues;
  }

  private parseVttTime(str: string): number {
    const parts = str.trim().split(':');
    if (parts.length === 3) {
      return Number(parts[0]) * 3600 + Number(parts[1]) * 60 + Number(parts[2]);
    }
    return Number(parts[0]) * 60 + Number(parts[1]);
  }
}
