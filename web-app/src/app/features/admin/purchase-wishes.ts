import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { firstValueFrom } from 'rxjs';
import { tmdbImageUrl } from '../../core/catalog.service';

interface WishAggregate {
  tmdb_id: number;
  title: string;
  display_title: string;
  media_type: string | null;
  poster_path: string | null;
  release_year: number | null;
  season_number: number | null;
  vote_count: number;
  voters: string[];
  lifecycle_stage: string;
  lifecycle_label: string;
}

const STATUS_OPTIONS = [
  { value: 'ORDERED', label: 'Ordered' },
  { value: 'NEEDS_ASSISTANCE', label: 'Needs Assistance' },
  { value: 'NOT_AVAILABLE', label: 'Not Available' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'OWNED', label: 'Already Owned' },
];

@Component({
  selector: 'app-purchase-wishes',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, MatTableModule, MatButtonModule, MatMenuModule],
  templateUrl: './purchase-wishes.html',
  styleUrl: './purchase-wishes.scss',
})
export class PurchaseWishesComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly wishes = signal<WishAggregate[]>([]);
  readonly columns = ['poster', 'title', 'year', 'type', 'votes', 'voters', 'status'];
  readonly statusOptions = STATUS_OPTIONS;

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

  async setStatus(wish: WishAggregate, status: string): Promise<void> {
    await firstValueFrom(this.http.post('/api/v2/admin/purchase-wishes/set-status', {
      tmdb_id: wish.tmdb_id,
      media_type: wish.media_type,
      season_number: wish.season_number,
      status,
    }));
    await this.refresh();
  }

  posterUrl(path: string): string { return tmdbImageUrl(path, 'w92')!; }

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
