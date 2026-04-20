import { ChangeDetectionStrategy, Component, OnInit, inject, input, output, signal, computed } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, TagCard } from '../../core/catalog.service';

/**
 * Reusable picker for choosing an existing tag (or creating a new one).
 * Stays presentational — the parent decides what to do with the picked
 * tag. Caller can pass [excludeTagIds] to filter out tags the target
 * already has.
 *
 * Used by:
 *   - Title detail page (album / book / movie) — admin adds a tag to a title.
 *   - Per-track row on album detail (phase B) — admin tags an individual track.
 */
@Component({
  selector: 'app-tag-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './tag-picker.html',
  styleUrl: './tag-picker.scss',
})
export class TagPickerComponent implements OnInit {
  private readonly catalog = inject(CatalogService);

  readonly heading = input<string>('Pick a tag');
  /** Tag ids that should NOT appear in the list (already-attached tags). */
  readonly excludeTagIds = input<number[]>([]);

  readonly picked = output<TagCard>();
  /**
   * Fired when the user creates a new tag from the picker. Emits the
   * fresh TagCard. Parent decides whether to immediately attach it.
   */
  readonly created = output<TagCard>();
  readonly cancelled = output<void>();

  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly allTags = signal<TagCard[]>([]);

  readonly visibleTags = computed(() => {
    const exclude = new Set(this.excludeTagIds());
    return this.allTags().filter(t => !exclude.has(t.id));
  });

  async ngOnInit(): Promise<void> {
    try {
      const resp = await this.catalog.getTags();
      this.allTags.set(resp.tags);
    } catch {
      this.error.set('Failed to load tags.');
    } finally {
      this.loading.set(false);
    }
  }

  pick(t: TagCard): void {
    if (this.busy()) return;
    this.picked.emit(t);
  }

  async createNew(): Promise<void> {
    const name = window.prompt('Name your new tag');
    if (name === null) return;
    const trimmed = name.trim();
    if (!trimmed) return;
    this.busy.set(true);
    try {
      const { id } = await this.catalog.createTag(trimmed, '#6B7280');
      // Refetch so we get the server-computed text_color and any
      // normalization. Find the freshly-created tag by id.
      const resp = await this.catalog.getTags();
      this.allTags.set(resp.tags);
      const fresh = resp.tags.find(t => t.id === id);
      if (fresh) this.created.emit(fresh);
    } finally {
      this.busy.set(false);
    }
  }
}
