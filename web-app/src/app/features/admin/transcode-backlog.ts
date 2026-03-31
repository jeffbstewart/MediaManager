import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';

interface BacklogRow {
  title_id: number;
  title_name: string;
  media_type: string;
  release_year: number | null;
  poster_url: string | null;
  request_count: number;
  popularity: number;
  is_wished: boolean;
}

@Component({
  selector: 'app-transcode-backlog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatProgressSpinnerModule, MatTableModule, MatPaginatorModule, MatButtonModule],
  templateUrl: './transcode-backlog.html',
  styleUrl: './transcode-backlog.scss',
})
export class TranscodeBacklogComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly rows = signal<BacklogRow[]>([]);
  readonly total = signal(0);
  readonly search = signal('');
  readonly page = signal(0);
  readonly pageSize = signal(50);

  readonly columns = ['poster', 'title', 'type', 'year', 'requests', 'request'];

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const params: Record<string, string> = {
        page: this.page().toString(),
        size: this.pageSize().toString(),
      };
      const s = this.search().trim();
      if (s) params['search'] = s;

      const data = await firstValueFrom(this.http.get<{ rows: BacklogRow[]; total: number }>(
        '/api/v2/admin/transcode-backlog', { params }
      ));
      this.rows.set(data.rows);
      this.total.set(data.total);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  async onSearchInput(event: Event): Promise<void> {
    this.search.set((event.target as HTMLInputElement).value);
    this.page.set(0);
    await this.refresh();
  }

  async onPage(event: PageEvent): Promise<void> {
    this.page.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
    await this.refresh();
  }

  async toggleWish(row: BacklogRow): Promise<void> {
    if (row.is_wished) {
      // Find and remove the wish
      const wishList = await firstValueFrom(this.http.get<{ transcode_wishes: { id: number; title_id: number }[] }>('/api/v2/wishlist'));
      const wish = wishList.transcode_wishes.find(w => w.title_id === row.title_id);
      if (wish) await firstValueFrom(this.http.delete(`/api/v2/wishlist/transcode/${wish.id}`));
    } else {
      await firstValueFrom(this.http.post(`/api/v2/wishlist/transcode/${row.title_id}`, {}));
    }
    await this.refresh();
  }
}
