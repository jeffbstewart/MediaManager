import { Component, ChangeDetectionStrategy, OnInit, signal, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, SmartPlaylistDetail, PlaylistTrackEntry } from '../../core/catalog.service';
import { PlaybackQueueService, QueuedTrack } from '../../core/playback-queue.service';
import { AppRoutes } from '../../core/routes';

/**
 * Read-only smart playlist viewer. Shares the look of [PlaylistDetailComponent]
 * but strips edit / duplicate / hero / privacy — the underlying view
 * is computed, not stored.
 */
@Component({
  selector: 'app-smart-playlist-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './smart-playlist-detail.html',
  styleUrl: './playlist-detail.scss',
})
export class SmartPlaylistDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly catalog = inject(CatalogService);
  private readonly queue = inject(PlaybackQueueService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly playlist = signal<SmartPlaylistDetail | null>(null);

  async ngOnInit(): Promise<void> {
    const key = this.route.snapshot.paramMap.get('key');
    if (!key) {
      this.error.set('Bad smart playlist key');
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    try {
      this.playlist.set(await this.catalog.getSmartPlaylist(key));
    } catch {
      this.error.set('Smart playlist not found.');
    } finally {
      this.loading.set(false);
    }
  }

  playFrom(entry: PlaylistTrackEntry): void {
    const p = this.playlist();
    if (!p) return;
    const tracks: QueuedTrack[] = p.tracks
      .filter(t => t.playable)
      .map(t => ({
        trackId: t.track_id,
        trackName: t.track_name,
        durationSeconds: t.duration_seconds,
        albumTitleId: t.title_id,
        albumName: t.title_name ?? '',
        albumPosterUrl: t.poster_url,
        primaryArtistName: null,
      }));
    const startIdx = tracks.findIndex(t => t.trackId === entry.track_id);
    this.queue.playTracks(tracks, Math.max(0, startIdx));
  }

  playAll(): void {
    const p = this.playlist();
    if (!p) return;
    const first = p.tracks.find(t => t.playable);
    if (first) this.playFrom(first);
  }

  formatTrackDuration(seconds: number | null): string {
    return seconds == null ? '' : formatDuration(seconds);
  }

  durationLabel(): string {
    const p = this.playlist();
    return p ? formatDuration(p.total_duration_seconds) : '';
  }
}

function formatDuration(seconds: number): string {
  if (!seconds || seconds < 0) return '0:00';
  const total = Math.floor(seconds);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) return `${h}:${pad2(m)}:${pad2(s)}`;
  return `${m}:${pad2(s)}`;
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : `${n}`;
}
