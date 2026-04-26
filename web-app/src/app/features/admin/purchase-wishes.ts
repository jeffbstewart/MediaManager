import { Component, inject, signal, computed, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatChipsModule } from '@angular/material/chips';
import { firstValueFrom } from 'rxjs';
import { tmdbImageUrl } from '../../core/catalog.service';

/**
 * Common fields across both wish types. The server emits a `wish_type`
 * discriminator on each row — 'MEDIA' for movie / TV titles (TMDB-keyed,
 * may carry season_number) or 'ALBUM' for CD wishes (MusicBrainz-keyed).
 */
interface WishAggregate {
  wish_type: 'MEDIA' | 'ALBUM';
  title: string;
  display_title: string;
  release_year: number | null;
  vote_count: number;
  voters: string[];
  lifecycle_stage: string;
  lifecycle_label: string;

  // MEDIA-only
  tmdb_id?: number;
  media_type?: string | null;
  poster_path?: string | null;
  season_number?: number | null;

  // ALBUM-only
  release_group_id?: string;
  primary_artist?: string | null;
  is_compilation?: boolean;
  cover_release_id?: string | null;
}

const STATUS_OPTIONS = [
  { value: 'ORDERED', label: 'Ordered' },
  { value: 'NEEDS_ASSISTANCE', label: 'Needs Assistance' },
  { value: 'NOT_AVAILABLE', label: 'Not Available' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'OWNED', label: 'Already Owned' },
];

/**
 * Lifecycle stages with their default visibility. READY_TO_WATCH and
 * NOT_FEASIBLE start unchecked because the admin's working surface is
 * "what still needs action" — already-acquired and known-impossible
 * rows are noise unless the admin explicitly opts in.
 */
const LIFECYCLE_FILTERS: { stage: string; label: string; defaultOn: boolean }[] = [
  { stage: 'WISHED_FOR',              label: 'Wished for',              defaultOn: true  },
  { stage: 'NEEDS_ASSISTANCE',        label: 'Needs assistance',        defaultOn: true  },
  { stage: 'ORDERED',                 label: 'Ordered',                 defaultOn: true  },
  { stage: 'IN_HOUSE_PENDING_NAS',    label: 'In house, pending NAS',   defaultOn: true  },
  { stage: 'ON_NAS_PENDING_DESKTOP',  label: 'On NAS, pending desktop', defaultOn: true  },
  { stage: 'READY_TO_WATCH',          label: 'Ready to watch',          defaultOn: false },
  { stage: 'NOT_FEASIBLE',            label: 'Not feasible',            defaultOn: false },
  { stage: 'WONT_ORDER',              label: 'Won\'t order',            defaultOn: true  },
];

@Component({
  selector: 'app-purchase-wishes',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, MatTableModule, MatButtonModule, MatMenuModule, MatChipsModule],
  templateUrl: './purchase-wishes.html',
  styleUrl: './purchase-wishes.scss',
})
export class PurchaseWishesComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly wishes = signal<WishAggregate[]>([]);
  readonly columns = ['poster', 'title', 'year', 'type', 'votes', 'voters', 'status'];
  readonly statusOptions = STATUS_OPTIONS;
  readonly lifecycleFilters = LIFECYCLE_FILTERS;
  readonly enabledStages = signal<Set<string>>(
    new Set(LIFECYCLE_FILTERS.filter(f => f.defaultOn).map(f => f.stage))
  );

  /** Wishes filtered to the currently-enabled lifecycle stages. */
  readonly visibleWishes = computed(() => {
    const enabled = this.enabledStages();
    return this.wishes().filter(w => enabled.has(w.lifecycle_stage));
  });

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const data = await firstValueFrom(this.http.get<{ wishes: WishAggregate[] }>('/api/v2/admin/purchase-wishes'));
      this.wishes.set(data.wishes);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  toggleStage(stage: string): void {
    const next = new Set(this.enabledStages());
    if (next.has(stage)) next.delete(stage);
    else next.add(stage);
    this.enabledStages.set(next);
  }

  isStageEnabled(stage: string): boolean {
    return this.enabledStages().has(stage);
  }

  async setStatus(wish: WishAggregate, status: string): Promise<void> {
    // Status updates are MEDIA-only — the existing endpoint keys on tmdb_id.
    if (wish.wish_type !== 'MEDIA') return;
    await firstValueFrom(this.http.post('/api/v2/admin/purchase-wishes/set-status', {
      tmdb_id: wish.tmdb_id,
      media_type: wish.media_type,
      season_number: wish.season_number,
      status,
    }));
    await this.refresh();
  }

  posterUrl(path: string): string { return tmdbImageUrl(path, 'w92')!; }

  /** Cover Art Archive thumbnail URL for an album release. */
  albumCoverUrl(releaseId: string): string {
    return `https://coverartarchive.org/release/${releaseId}/front-250`;
  }

  /** Display string for the Type column — "Movie" / "TV" / "CD". */
  typeLabel(wish: WishAggregate): string {
    if (wish.wish_type === 'ALBUM') return 'CD';
    return wish.media_type === 'TV' ? 'TV' : 'Movie';
  }

  stageColor(stage: string): string {
    // Solid-pill backgrounds: paired with white text in the template.
    // Each color is dark enough to clear 4.5:1 against white text.
    switch (stage) {
      case 'READY_TO_WATCH': return '#2e7d32';
      case 'ON_NAS_PENDING_DESKTOP':
      case 'IN_HOUSE_PENDING_NAS':
      case 'ORDERED': return '#1565c0';
      case 'NOT_FEASIBLE':
      case 'WONT_ORDER': return '#c62828';
      case 'NEEDS_ASSISTANCE': return '#b45309';
      default: return '#6d4c00';
    }
  }
}
