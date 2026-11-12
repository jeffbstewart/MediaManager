import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { CatalogService } from '../../core/catalog.service';
import { PlaybackQueueService } from '../../core/playback-queue.service';
import { AppRoutes } from '../../core/routes';
import { AddToPlaylistComponent } from '../add-to-playlist/add-to-playlist';

/**
 * Drop-up panel that shows the audio player's current queue — current
 * track highlighted, upcoming tracks below, already-played tracks dim
 * above. Clicking a row jumps playback to that track. In RADIO mode
 * the panel also surfaces the seed info and a note that the tail
 * auto-refills from Last.fm.
 *
 * Phase 2 additions:
 *   - "Save queue as playlist" — the whole current queue becomes a new
 *     owned playlist; one-shot prompt for the name.
 *   - "Add current track to..." — opens the [AddToPlaylistComponent]
 *     picker, anchored over the panel.
 *   - "Save radio session as playlist" — same as save-queue but only
 *     surfaced when [queueMode] is RADIO.
 */
@Component({
  selector: 'app-queue-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatButtonModule, AddToPlaylistComponent],
  templateUrl: './queue-panel.html',
  styleUrl: './queue-panel.scss',
})
export class QueuePanelComponent {
  readonly queue = inject(PlaybackQueueService);
  private readonly catalog = inject(CatalogService);
  private readonly router = inject(Router);
  readonly routes = AppRoutes;

  readonly open = input<boolean>(false);
  readonly closeRequested = output<void>();

  readonly pickerOpen = signal(false);
  readonly busy = signal(false);

  readonly currentTrackId = computed(() => this.queue.currentTrack()?.trackId ?? null);
  readonly hasQueue = computed(() => this.queue.queueSnapshot().length > 0);

  onTrackClick(index: number): void {
    this.queue.jumpTo(index);
  }

  formatDuration(seconds: number | null): string | null {
    if (!seconds || seconds < 1) return null;
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  }

  /**
   * Capture the entire current queue into a new owned playlist. Works
   * whether queueMode is EXPLICIT (library shuffle, ad-hoc) or RADIO
   * (the auto-refilled tail). Uses a synchronous prompt for naming so
   * the action stays one click away.
   */
  async saveQueueAsPlaylist(): Promise<void> {
    if (this.busy()) return;
    const ids = this.queue.currentQueueTrackIds();
    if (ids.length === 0) return;
    const defaultName = this.queue.queueMode() === 'RADIO'
      ? `Radio: ${this.queue.radioSeed()?.seed_name ?? 'session'}`
      : 'My queue';
    const name = window.prompt('Name the new playlist', defaultName);
    if (name === null) return;
    const trimmed = name.trim();
    if (!trimmed) return;
    this.busy.set(true);
    try {
      const created = await this.catalog.createPlaylist(trimmed, null);
      await this.catalog.addTracksToPlaylist(created.id, ids);
      this.closeRequested.emit();
      await this.router.navigate([this.routes.playlist(created.id)]);
    } finally {
      this.busy.set(false);
    }
  }

  /** Open the "add current track to..." picker. */
  openPicker(): void {
    if (this.currentTrackId() == null) return;
    this.pickerOpen.set(true);
  }

  closePicker(): void {
    this.pickerOpen.set(false);
  }

  onPickerPicked(_evt: { playlistId: number; created: boolean }): void {
    this.pickerOpen.set(false);
  }

  /** Track ids passed to the picker — just the currently-playing track. */
  currentTrackIds(): number[] {
    const tid = this.currentTrackId();
    return tid != null ? [tid] : [];
  }
}
