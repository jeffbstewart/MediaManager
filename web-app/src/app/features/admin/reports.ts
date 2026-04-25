import {
  Component, inject, signal, OnInit, ChangeDetectionStrategy,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatChipsModule } from '@angular/material/chips';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { firstValueFrom } from 'rxjs';
import { CatalogService } from '../../core/catalog.service';
import { FeatureService } from '../../core/feature.service';

interface ReportRow {
  id: number;
  reporter_name: string;
  title_id: number | null;
  title_name: string | null;
  season_number: number | null;
  episode_number: number | null;
  description: string;
  status: string;
  admin_notes: string | null;
  resolved_by_name: string | null;
  created_at: string;
  updated_at: string;
}

@Component({
  selector: 'app-reports',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    MatIconModule,
    MatButtonModule,
    MatTableModule,
    MatPaginatorModule,
    MatChipsModule,
    MatMenuModule,
    MatDividerModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './reports.html',
  styleUrl: './reports.scss',
})
export class ReportsComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly catalog = inject(CatalogService);
  private readonly features = inject(FeatureService);

  readonly loading = signal(true);
  readonly rows = signal<ReportRow[]>([]);
  readonly total = signal(0);
  readonly statusFilter = signal('OPEN');
  readonly page = signal(0);
  readonly pageSize = signal(50);

  readonly columns = ['reporter', 'title', 'description', 'status', 'date', 'actions'];

  // Resolve dialog
  readonly resolveOpen = signal(false);
  readonly resolveRow = signal<ReportRow | null>(null);
  readonly resolveStatus = signal('RESOLVED');
  readonly resolveNotes = signal('');

  // Delete-catalog-entry dialog. Distinct from Resolve so the
  // confirmation copy can warn about NAS files staying behind and the
  // user can't bypass the explicit click on a destructive button.
  readonly deleteMediaOpen = signal(false);
  readonly deleteMediaRow = signal<ReportRow | null>(null);
  readonly deleteMediaNotes = signal('');
  readonly deletingMedia = signal(false);

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
      if (this.statusFilter()) params['status'] = this.statusFilter();

      const data = await firstValueFrom(this.http.get<{
        rows: ReportRow[];
        total: number;
        open_count: number;
      }>('/api/v2/admin/reports', { params }));

      this.rows.set(data.rows);
      this.total.set(data.total);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  async setStatus(status: string): Promise<void> {
    this.statusFilter.set(status);
    this.page.set(0);
    await this.refresh();
  }

  async onPage(event: PageEvent): Promise<void> {
    this.page.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
    await this.refresh();
  }

  formatDate(iso: string | null): string {
    if (!iso) return '';
    const d = new Date(iso);
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  }

  // --- Resolve / Dismiss ---

  openResolve(row: ReportRow, status: string): void {
    this.resolveRow.set(row);
    this.resolveStatus.set(status);
    this.resolveNotes.set('');
    this.resolveOpen.set(true);
  }

  closeResolve(): void {
    this.resolveOpen.set(false);
    this.resolveRow.set(null);
  }

  async submitResolve(): Promise<void> {
    const row = this.resolveRow();
    if (!row) return;
    try {
      await firstValueFrom(this.http.post(`/api/v2/admin/reports/${row.id}/resolve`, {
        status: this.resolveStatus(),
        notes: this.resolveNotes().trim() || null,
      }));
      this.closeResolve();
      await this.refresh();
      await this.refreshFeatures();
    } catch { /* ignore */ }
  }

  // --- Delete catalog entry ---

  openDeleteMedia(row: ReportRow): void {
    this.deleteMediaRow.set(row);
    this.deleteMediaNotes.set('');
    this.deleteMediaOpen.set(true);
  }

  closeDeleteMedia(): void {
    this.deleteMediaOpen.set(false);
    this.deleteMediaRow.set(null);
  }

  async submitDeleteMedia(): Promise<void> {
    const row = this.deleteMediaRow();
    if (!row) return;
    this.deletingMedia.set(true);
    try {
      await firstValueFrom(this.http.post(`/api/v2/admin/reports/${row.id}/delete-media`, {
        notes: this.deleteMediaNotes().trim() || null,
      }));
      this.closeDeleteMedia();
      await this.refresh();
      await this.refreshFeatures();
    } catch { /* server error toast TBD; keep dialog open so user can retry */ }
    finally { this.deletingMedia.set(false); }
  }

  async reopen(row: ReportRow): Promise<void> {
    try {
      await firstValueFrom(this.http.post(`/api/v2/admin/reports/${row.id}/reopen`, {}));
      await this.refresh();
      await this.refreshFeatures();
    } catch { /* ignore */ }
  }

  private async refreshFeatures(): Promise<void> {
    try {
      const flags = await this.catalog.getFeatures();
      this.features.update(flags);
    } catch { /* non-fatal */ }
  }
}
