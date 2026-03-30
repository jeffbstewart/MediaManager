import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { firstValueFrom } from 'rxjs';

interface LinkedTitle { title_id: number; name: string; release_year: number | null; poster_url: string | null; disc_number: number; }
interface ExpandItem { id: number; upc: string | null; product_name: string | null; media_format: string; title_count: number; linked_titles: LinkedTitle[]; }
interface TmdbResult { tmdb_id: number; title: string; media_type: string; release_year: number | null; poster_path: string | null; }

@Component({
  selector: 'app-expand',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, MatTableModule, MatButtonModule, MatCardModule, MatChipsModule],
  templateUrl: './expand.html',
  styleUrl: './expand.scss',
})
export class ExpandComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly items = signal<ExpandItem[]>([]);
  readonly columns = ['product', 'format', 'titles'];

  // Dialog
  readonly dialogOpen = signal(false);
  readonly dialogItem = signal<ExpandItem | null>(null);
  readonly dialogTitles = signal<LinkedTitle[]>([]);
  readonly searchQuery = signal('');
  readonly searchType = signal<'movie' | 'tv'>('movie');
  readonly searchResults = signal<TmdbResult[]>([]);
  readonly searching = signal(false);

  async ngOnInit(): Promise<void> { await this.refresh(); }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const data = await firstValueFrom(this.http.get<{ items: ExpandItem[] }>('/api/v2/admin/expand'));
      this.items.set(data.items);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  openDialog(item: ExpandItem): void {
    this.dialogItem.set(item);
    this.dialogTitles.set(item.linked_titles);
    this.searchQuery.set('');
    this.searchResults.set([]);
    this.dialogOpen.set(true);
  }

  closeDialog(): void { this.dialogOpen.set(false); this.dialogItem.set(null); }

  toggleSearchType(): void { this.searchType.update(t => t === 'movie' ? 'tv' : 'movie'); }

  async searchTmdb(event: Event): Promise<void> {
    const q = (event.target as HTMLInputElement).value;
    this.searchQuery.set(q);
    if (q.trim().length < 2) { this.searchResults.set([]); return; }
    this.searching.set(true);
    try {
      const data = await firstValueFrom(this.http.get<{ results: TmdbResult[] }>(
        '/api/v2/admin/expand/search-tmdb', { params: { q: q.trim(), type: this.searchType() } }));
      this.searchResults.set(data.results);
    } catch { /* ignore */ }
    this.searching.set(false);
  }

  async addTitle(result: TmdbResult): Promise<void> {
    const item = this.dialogItem();
    if (!item) return;
    const resp = await firstValueFrom(this.http.post<{ ok: boolean; title_id: number; title_name: string; disc_number: number }>(
      `/api/v2/admin/expand/${item.id}/add-title`, { tmdb_id: result.tmdb_id, media_type: result.media_type }));
    if (resp.ok) {
      this.dialogTitles.update(t => [...t, { title_id: resp.title_id, name: resp.title_name, release_year: result.release_year, poster_url: result.poster_path ? `https://image.tmdb.org/t/p/w92${result.poster_path}` : null, disc_number: resp.disc_number }]);
    }
  }

  async removeTitle(title: LinkedTitle): Promise<void> {
    const item = this.dialogItem();
    if (!item) return;
    await firstValueFrom(this.http.delete(`/api/v2/admin/expand/${item.id}/title/${title.title_id}`));
    this.dialogTitles.update(t => t.filter(x => x.title_id !== title.title_id));
  }

  async markExpanded(): Promise<void> {
    const item = this.dialogItem();
    if (!item) return;
    await firstValueFrom(this.http.post(`/api/v2/admin/expand/${item.id}/mark-expanded`, {}));
    this.closeDialog();
    await this.refresh();
  }

  async notMultiPack(): Promise<void> {
    const item = this.dialogItem();
    if (!item) return;
    await firstValueFrom(this.http.post(`/api/v2/admin/expand/${item.id}/not-multipack`, {}));
    this.closeDialog();
    await this.refresh();
  }

  posterUrl(path: string): string { return `https://image.tmdb.org/t/p/w92${path}`; }
}
