import { ChangeDetectionStrategy, Component, OnInit, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, PlaylistSummary } from '../../core/catalog.service';

/**
 * Generic "Add to playlist" picker. Lists the current user's owned
 * playlists plus a "Create new" affordance. Used by:
 *
 * - Bottom-bar queue panel: append the current track to a playlist.
 * - Album / track pages: add this track or the whole album.
 *
 * Pure presentational — the parent provides the trackIds (or anything
 * else to send) and decides what happens after [picked] fires. Picker
 * closes itself on selection.
 */
@Component({
  selector: 'app-add-to-playlist',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './add-to-playlist.html',
  styleUrl: './add-to-playlist.scss',
})
export class AddToPlaylistComponent implements OnInit {
  private readonly catalog = inject(CatalogService);

  /** Track ids to append. Empty => caller is using the picker for a destination only. */
  readonly trackIds = input<number[]>([]);
  /** Caller-provided heading; defaults to "Add to playlist". */
  readonly heading = input<string>('Add to playlist');

  /** Fired with the chosen playlist id (existing or freshly created). */
  readonly picked = output<{ playlistId: number; created: boolean }>();
  readonly cancelled = output<void>();

  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly playlists = signal<PlaylistSummary[]>([]);
  readonly error = signal('');

  async ngOnInit(): Promise<void> {
    try {
      const resp = await this.catalog.listPlaylists('mine');
      this.playlists.set(resp.playlists);
    } catch {
      this.error.set('Failed to load your playlists.');
    } finally {
      this.loading.set(false);
    }
  }

  async pick(p: PlaylistSummary): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    try {
      const ids = this.trackIds();
      if (ids.length > 0) await this.catalog.addTracksToPlaylist(p.id, ids);
      this.picked.emit({ playlistId: p.id, created: false });
    } finally {
      this.busy.set(false);
    }
  }

  async createAndPick(): Promise<void> {
    const name = window.prompt('Name your new playlist');
    if (name === null) return;
    const trimmed = name.trim();
    if (!trimmed) return;
    this.busy.set(true);
    try {
      const created = await this.catalog.createPlaylist(trimmed, null);
      const ids = this.trackIds();
      if (ids.length > 0) await this.catalog.addTracksToPlaylist(created.id, ids);
      this.picked.emit({ playlistId: created.id, created: true });
    } finally {
      this.busy.set(false);
    }
  }
}
