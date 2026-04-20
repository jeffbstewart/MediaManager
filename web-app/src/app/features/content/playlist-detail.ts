import { Component, ChangeDetectionStrategy, OnInit, signal, inject, computed } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { CatalogService, PlaylistDetail, PlaylistTrackEntry } from '../../core/catalog.service';
import { PlaybackQueueService, QueuedTrack } from '../../core/playback-queue.service';
import { AppRoutes } from '../../core/routes';

/**
 * Playlist detail page (phase 2).
 *
 * - Drag-drop reorder via Angular CDK; up/down arrows kept for keyboard a11y.
 * - Bulk-select with a checkbox column + "Remove selected" button.
 * - Owner-only privacy toggle in the header.
 * - "Resume from {track}" banner when the user has a saved cursor.
 * - Plays attach a playlistContext so the queue service can post resume
 *   updates as the user moves through the list.
 */
@Component({
  selector: 'app-playlist-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    DragDropModule,
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSlideToggleModule,
  ],
  templateUrl: './playlist-detail.html',
  styleUrl: './playlist-detail.scss',
})
export class PlaylistDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly catalog = inject(CatalogService);
  private readonly queue = inject(PlaybackQueueService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly playlist = signal<PlaylistDetail | null>(null);
  readonly busy = signal(false);
  readonly selected = signal<Set<number>>(new Set<number>());

  readonly heroPosterUrl = computed(() => {
    const p = this.playlist();
    if (!p) return null;
    return p.hero_poster_url ?? p.tracks[0]?.poster_url ?? null;
  });

  readonly durationLabel = computed(() => {
    const p = this.playlist();
    if (!p) return '';
    return formatDuration(p.total_duration_seconds);
  });

  readonly resumeTrack = computed<PlaylistTrackEntry | null>(() => {
    const p = this.playlist();
    if (!p?.resume) return null;
    return p.tracks.find(t => t.playlist_track_id === p.resume!.playlist_track_id) ?? null;
  });

  readonly anySelected = computed(() => this.selected().size > 0);

  async ngOnInit(): Promise<void> {
    const id = Number(this.route.snapshot.paramMap.get('playlistId'));
    if (!id) {
      this.error.set('Bad playlist id');
      this.loading.set(false);
      return;
    }
    await this.refresh(id);
  }

  private async refresh(id: number): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.playlist.set(await this.catalog.getPlaylist(id));
      this.selected.set(new Set<number>());
    } catch {
      this.error.set('Failed to load playlist (or it may be private).');
    } finally {
      this.loading.set(false);
    }
  }

  private buildQueue(p: PlaylistDetail): QueuedTrack[] {
    return p.tracks
      .filter(t => t.playable)
      .map(t => ({
        trackId: t.track_id,
        trackName: t.track_name,
        durationSeconds: t.duration_seconds,
        albumTitleId: t.title_id,
        albumName: t.title_name ?? '',
        albumPosterUrl: t.poster_url,
        primaryArtistName: null,
        playlistContext: { playlistId: p.id, playlistTrackId: t.playlist_track_id },
      }));
  }

  /** Replace the audio queue with this playlist starting at [position]. */
  playFrom(entry: PlaylistTrackEntry): void {
    const p = this.playlist();
    if (!p) return;
    const tracks = this.buildQueue(p);
    const startIdx = tracks.findIndex(t => t.trackId === entry.track_id);
    this.queue.playTracks(tracks, Math.max(0, startIdx));
  }

  /** Play from the top — ignores any non-playable rows. */
  playAll(): void {
    const p = this.playlist();
    if (!p) return;
    const first = p.tracks.find(t => t.playable);
    if (first) this.playFrom(first);
  }

  /** Resume from the saved cursor track. */
  resumePlay(): void {
    const target = this.resumeTrack();
    if (target) this.playFrom(target);
  }

  async clearResume(): Promise<void> {
    const p = this.playlist();
    if (!p) return;
    await this.catalog.clearPlaylistProgress(p.id);
    await this.refresh(p.id);
  }

  async rename(): Promise<void> {
    const p = this.playlist();
    if (!p || !p.is_owner) return;
    const name = window.prompt('Rename playlist', p.name);
    if (name === null) return;
    const trimmed = name.trim();
    if (!trimmed || trimmed === p.name) return;
    await this.runOwnerAction(async () => {
      await this.catalog.renamePlaylist(p.id, trimmed, p.description);
    });
  }

  async editDescription(): Promise<void> {
    const p = this.playlist();
    if (!p || !p.is_owner) return;
    const desc = window.prompt('Description', p.description ?? '');
    if (desc === null) return;
    await this.runOwnerAction(async () => {
      await this.catalog.renamePlaylist(p.id, p.name, desc.trim() || null);
    });
  }

  async togglePrivacy(): Promise<void> {
    const p = this.playlist();
    if (!p || !p.is_owner) return;
    await this.runOwnerAction(() => this.catalog.setPlaylistPrivacy(p.id, !p.is_private));
  }

  async moveUp(entry: PlaylistTrackEntry): Promise<void> {
    const p = this.playlist();
    if (!p || !p.is_owner) return;
    const idx = p.tracks.findIndex(t => t.playlist_track_id === entry.playlist_track_id);
    if (idx <= 0) return;
    const ids = p.tracks.map(t => t.playlist_track_id);
    [ids[idx - 1], ids[idx]] = [ids[idx], ids[idx - 1]];
    await this.runOwnerAction(() => this.catalog.reorderPlaylist(p.id, ids));
  }

  async moveDown(entry: PlaylistTrackEntry): Promise<void> {
    const p = this.playlist();
    if (!p || !p.is_owner) return;
    const idx = p.tracks.findIndex(t => t.playlist_track_id === entry.playlist_track_id);
    if (idx < 0 || idx >= p.tracks.length - 1) return;
    const ids = p.tracks.map(t => t.playlist_track_id);
    [ids[idx + 1], ids[idx]] = [ids[idx], ids[idx + 1]];
    await this.runOwnerAction(() => this.catalog.reorderPlaylist(p.id, ids));
  }

  /** CDK drag-drop handler. Optimistically reorders the list, then PUTs. */
  async onDrop(ev: CdkDragDrop<PlaylistTrackEntry[]>): Promise<void> {
    const p = this.playlist();
    if (!p || !p.is_owner) return;
    if (ev.previousIndex === ev.currentIndex) return;
    const next = [...p.tracks];
    moveItemInArray(next, ev.previousIndex, ev.currentIndex);
    // Optimistic: re-render with the new order so the row doesn't snap back.
    this.playlist.set({
      ...p,
      tracks: next.map((t, i) => ({ ...t, position: i })),
    });
    await this.runOwnerAction(() =>
      this.catalog.reorderPlaylist(p.id, next.map(t => t.playlist_track_id)));
  }

  async removeTrack(entry: PlaylistTrackEntry): Promise<void> {
    const p = this.playlist();
    if (!p || !p.is_owner) return;
    await this.runOwnerAction(() => this.catalog.removeTrackFromPlaylist(p.id, entry.playlist_track_id));
  }

  /** Bulk remove — uses reorder() with the survivors to drop the selected rows. */
  async removeSelected(): Promise<void> {
    const p = this.playlist();
    if (!p || !p.is_owner) return;
    const drop = this.selected();
    if (drop.size === 0) return;
    if (!window.confirm(`Remove ${drop.size} ${drop.size === 1 ? 'track' : 'tracks'} from "${p.name}"?`)) return;
    const survivors = p.tracks
      .filter(t => !drop.has(t.playlist_track_id))
      .map(t => t.playlist_track_id);
    await this.runOwnerAction(() => this.catalog.reorderPlaylist(p.id, survivors));
  }

  toggleSelect(ptId: number, ev?: Event): void {
    if (ev) ev.stopPropagation();
    const next = new Set(this.selected());
    if (next.has(ptId)) next.delete(ptId); else next.add(ptId);
    this.selected.set(next);
  }

  isSelected(ptId: number): boolean {
    return this.selected().has(ptId);
  }

  clearSelection(): void {
    this.selected.set(new Set<number>());
  }

  toggleSelectAll(): void {
    const p = this.playlist();
    if (!p) return;
    if (this.selected().size === p.tracks.length) {
      this.selected.set(new Set());
    } else {
      this.selected.set(new Set(p.tracks.map(t => t.playlist_track_id)));
    }
  }

  async setHero(entry: PlaylistTrackEntry): Promise<void> {
    const p = this.playlist();
    if (!p || !p.is_owner) return;
    const next = p.hero_track_id === entry.track_id ? null : entry.track_id;
    await this.runOwnerAction(() => this.catalog.setPlaylistHero(p.id, next));
  }

  async deletePlaylist(): Promise<void> {
    const p = this.playlist();
    if (!p || !p.is_owner) return;
    if (!window.confirm(`Delete "${p.name}" permanently?`)) return;
    this.busy.set(true);
    try {
      await this.catalog.deletePlaylist(p.id);
      await this.router.navigate([this.routes.playlists()]);
    } finally {
      this.busy.set(false);
    }
  }

  async duplicate(): Promise<void> {
    const p = this.playlist();
    if (!p) return;
    this.busy.set(true);
    try {
      const fork = await this.catalog.duplicatePlaylist(p.id);
      await this.router.navigate([this.routes.playlist(fork.id)]);
    } finally {
      this.busy.set(false);
    }
  }

  private async runOwnerAction(fn: () => Promise<unknown>): Promise<void> {
    const p = this.playlist();
    if (!p) return;
    this.busy.set(true);
    try {
      await fn();
      await this.refresh(p.id);
    } finally {
      this.busy.set(false);
    }
  }

  trackIcon(p: PlaylistTrackEntry): string {
    return this.playlist()?.hero_track_id === p.track_id ? 'star' : 'star_border';
  }

  formatTrackDuration(seconds: number | null): string {
    return seconds == null ? '' : formatDuration(seconds);
  }

  resumeLabel(): string {
    const t = this.resumeTrack();
    const r = this.playlist()?.resume;
    if (!t || !r) return '';
    return `Resume "${t.track_name}" at ${formatDuration(r.position_seconds)}`;
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
