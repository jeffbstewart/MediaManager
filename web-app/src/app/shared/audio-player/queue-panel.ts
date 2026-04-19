import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { PlaybackQueueService } from '../../core/playback-queue.service';

/**
 * Drop-up panel that shows the audio player's current queue — current
 * track highlighted, upcoming tracks below, already-played tracks dim
 * above. Clicking a row jumps playback to that track. In RADIO mode
 * the panel also surfaces the seed info and a note that the tail
 * auto-refills from Last.fm.
 *
 * Mounted inside [AudioPlayerComponent] so it can position itself
 * absolutely against the player bar.
 */
@Component({
  selector: 'app-queue-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatButtonModule],
  templateUrl: './queue-panel.html',
  styleUrl: './queue-panel.scss',
})
export class QueuePanelComponent {
  readonly queue = inject(PlaybackQueueService);
  readonly open = input<boolean>(false);
  readonly closeRequested = output<void>();

  onTrackClick(index: number): void {
    this.queue.jumpTo(index);
  }

  formatDuration(seconds: number | null): string | null {
    if (!seconds || seconds < 1) return null;
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  }
}
