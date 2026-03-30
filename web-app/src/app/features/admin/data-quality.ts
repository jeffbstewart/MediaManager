import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { firstValueFrom } from 'rxjs';

interface DqRow {
  title_id: number; name: string; poster_url: string | null;
  release_year: number | null; media_type: string; content_rating: string | null;
  enrichment_status: string; hidden: boolean; tmdb_id: number | null;
  issues: string[];
}

@Component({
  selector: 'app-data-quality',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatProgressSpinnerModule, MatTableModule, MatPaginatorModule, MatButtonModule, MatChipsModule, MatMenuModule, MatDividerModule],
  templateUrl: './data-quality.html',
  styleUrl: './data-quality.scss',
})
export class DataQualityComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly rows = signal<DqRow[]>([]);
  readonly total = signal(0);
  readonly needsAttention = signal(0);
  readonly search = signal('');
  readonly statusFilter = signal('NEEDS_ATTENTION');
  readonly showHidden = signal(false);
  readonly page = signal(0);
  readonly pageSize = signal(50);

  readonly columns = ['poster', 'title', 'year', 'type', 'status', 'issues', 'actions'];
  readonly statusOptions = ['', 'NEEDS_ATTENTION', 'PENDING', 'ENRICHED', 'FAILED', 'SKIPPED', 'REASSIGNMENT_REQUESTED', 'ABANDONED'];

  // Edit dialog
  readonly editOpen = signal(false);
  readonly editRow = signal<DqRow | null>(null);
  readonly editTmdbId = signal('');
  readonly editMediaType = signal('MOVIE');

  async ngOnInit(): Promise<void> { await this.refresh(); }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const params: Record<string, string> = { page: this.page().toString(), size: this.pageSize().toString() };
      if (this.search().trim()) params['search'] = this.search().trim();
      if (this.statusFilter()) params['status'] = this.statusFilter();
      if (this.showHidden()) params['show_hidden'] = 'true';
      const data = await firstValueFrom(this.http.get<{ rows: DqRow[]; total: number; needs_attention: number }>(
        '/api/v2/admin/data-quality', { params }));
      this.rows.set(data.rows); this.total.set(data.total); this.needsAttention.set(data.needs_attention);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  async onSearch(event: Event): Promise<void> { this.search.set((event.target as HTMLInputElement).value); this.page.set(0); await this.refresh(); }
  async setStatus(s: string): Promise<void> { this.statusFilter.set(s); this.page.set(0); await this.refresh(); }
  async toggleHidden(): Promise<void> { this.showHidden.update(v => !v); this.page.set(0); await this.refresh(); }
  async onPage(event: PageEvent): Promise<void> { this.page.set(event.pageIndex); this.pageSize.set(event.pageSize); await this.refresh(); }

  async reEnrich(row: DqRow): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/data-quality/${row.title_id}/re-enrich`, {}));
    await this.refresh();
  }

  async toggleRowHidden(row: DqRow): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/data-quality/${row.title_id}/toggle-hidden`, {}));
    await this.refresh();
  }

  async deleteTitle(row: DqRow): Promise<void> {
    if (!confirm(`Delete "${row.name}"? This cannot be undone.`)) return;
    await firstValueFrom(this.http.delete(`/api/v2/admin/data-quality/${row.title_id}`));
    await this.refresh();
  }

  openEdit(row: DqRow): void {
    this.editRow.set(row);
    this.editTmdbId.set(row.tmdb_id?.toString() ?? '');
    this.editMediaType.set(row.media_type);
    this.editOpen.set(true);
  }
  closeEdit(): void { this.editOpen.set(false); }

  async saveEdit(): Promise<void> {
    const row = this.editRow(); if (!row) return;
    const tmdbId = parseInt(this.editTmdbId(), 10);
    await firstValueFrom(this.http.post(`/api/v2/admin/data-quality/${row.title_id}/update`, {
      tmdb_id: isNaN(tmdbId) ? null : tmdbId, media_type: this.editMediaType()
    }));
    this.closeEdit(); await this.refresh();
  }

  statusLabel(s: string): string {
    switch (s) { case '': return 'All'; case 'NEEDS_ATTENTION': return 'Needs Attention'; case 'ENRICHED': return 'Enriched'; case 'FAILED': return 'Failed'; case 'PENDING': return 'Pending';
      case 'SKIPPED': return 'Skipped'; case 'REASSIGNMENT_REQUESTED': return 'Reassign'; case 'ABANDONED': return 'Abandoned'; default: return s; }
  }

  statusColor(s: string): string {
    switch (s) { case 'ENRICHED': return '#4caf50'; case 'FAILED': case 'ABANDONED': return '#f44336';
      case 'PENDING': case 'REASSIGNMENT_REQUESTED': return 'var(--mat-sys-primary, #bb86fc)'; default: return 'rgba(255,255,255,0.5)'; }
  }

  issueLabel(i: string): string {
    return i.replace('NO_', 'No ').replace('ENRICHMENT_', '').replace('_', ' ').toLowerCase();
  }
}
