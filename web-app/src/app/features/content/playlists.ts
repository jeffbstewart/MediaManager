import { Component, ChangeDetectionStrategy, OnInit, signal, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, PlaylistSummary, SmartPlaylistSummary } from '../../core/catalog.service';
import { PlaybackQueueService, QueuedTrack } from '../../core/playback-queue.service';
import { AppRoutes } from '../../core/routes';

/**
 * Playlists landing page. Two views: All (browseable, every playlist
 * in the system) and Mine (only the current user's). Both views
 * support a "Duplicate" affordance that forks the playlist into one
 * the current user owns — works regardless of who owns the source.
 *
 * Library shuffle: a single button on this page kicks off an ephemeral
 * shuffle of every playable track in the catalog and queues it into
 * the audio player. The user can then "Save as playlist" from the
 * bottom-bar to make the resulting order durable.
 */
@Component({
  selector: 'app-playlists',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatButtonModule, MatChipsModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './playlists.html',
  styleUrl: './playlists.scss',
})
export class PlaylistsComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  private readonly queue = inject(PlaybackQueueService);
  private readonly router = inject(Router);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly scope = signal<'all' | 'mine'>('all');
  readonly playlists = signal<PlaylistSummary[]>([]);
  readonly smartPlaylists = signal<SmartPlaylistSummary[]>([]);
  readonly creating = signal(false);
  readonly shuffling = signal(false);

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async setScope(scope: 'all' | 'mine'): Promise<void> {
    this.scope.set(scope);
    await this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const resp = await this.catalog.listPlaylists(this.scope());
      this.playlists.set(resp.playlists);
      // Smart playlists are global, so we only show them on the All view —
      // they're not "yours" any more than they are anyone else's.
      this.smartPlaylists.set(this.scope() === 'all' ? resp.smartPlaylists : []);
    } catch {
      this.error.set('Failed to load playlists.');
    } finally {
      this.loading.set(false);
    }
  }

  async createNew(): Promise<void> {
    const name = window.prompt('Name your new playlist');
    if (name === null) return;
    const trimmed = name.trim();
    if (!trimmed) return;
    this.creating.set(true);
    try {
      const created = await this.catalog.createPlaylist(trimmed, null);
      await this.router.navigate([this.routes.playlist(created.id)]);
    } finally {
      this.creating.set(false);
    }
  }

  /**
   * Fork the given playlist into one owned by the current user. Works
   * even when the user already owns it (you might want to keep a
   * frozen "good" version and edit the copy).
   */
  async duplicate(p: PlaylistSummary, ev: Event): Promise<void> {
    // Card click also navigates — stop the bubble so the user isn't
    // dropped into the source playlist before the fork is created.
    ev.preventDefault();
    ev.stopPropagation();
    const fork = await this.catalog.duplicatePlaylist(p.id);
    await this.router.navigate([this.routes.playlist(fork.id)]);
  }

  /**
   * Shuffle the entire playable library and queue it. Doesn't persist
   * — the user can hit "Save as playlist" from the queue panel after
   * the shuffle starts playing.
   */
  async shuffleLibrary(): Promise<void> {
    if (this.shuffling()) return;
    this.shuffling.set(true);
    try {
      const tracks = await this.catalog.libraryShuffle();
      if (tracks.length === 0) return;
      const queued: QueuedTrack[] = tracks.map(t => ({
        trackId: t.track_id,
        trackName: t.track_name,
        durationSeconds: t.duration_seconds,
        albumTitleId: t.title_id,
        albumName: t.title_name ?? '',
        albumPosterUrl: t.poster_url,
        primaryArtistName: null,
      }));
      this.queue.playTracks(queued, 0);
    } finally {
      this.shuffling.set(false);
    }
  }
}
