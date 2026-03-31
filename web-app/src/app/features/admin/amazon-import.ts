import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { firstValueFrom } from 'rxjs';

interface OrderRow {
  id: number; order_id: string; asin: string; product_name: string;
  order_date: string | null; unit_price: number | null; product_condition: string | null;
  order_status: string | null; linked_title: string | null; linked_media_item_id: number | null;
}

interface SearchItem { id: number; display_name: string; media_format: string; upc: string | null; }

@Component({
  selector: 'app-amazon-import',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, MatTableModule, MatPaginatorModule, MatButtonModule, MatCardModule, MatChipsModule],
  templateUrl: './amazon-import.html',
  styleUrl: './amazon-import.scss',
})
export class AmazonImportComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly rows = signal<OrderRow[]>([]);
  readonly total = signal(0);
  readonly stats = signal({ total: 0, media: 0, linked: 0 });
  readonly search = signal('');
  readonly mediaOnly = signal(false);
  readonly unlinkedOnly = signal(false);
  readonly hideCancelled = signal(true);
  readonly page = signal(0);
  readonly pageSize = signal(50);
  readonly columns = ['name', 'date', 'price', 'condition', 'linked', 'actions'];

  // Upload
  readonly uploading = signal(false);
  readonly uploadResult = signal('');

  // Link dialog
  readonly linkOpen = signal(false);
  readonly linkOrder = signal<OrderRow | null>(null);
  readonly linkQuery = signal('');
  readonly linkResults = signal<SearchItem[]>([]);
  readonly linkSearching = signal(false);

  async ngOnInit(): Promise<void> { await this.refresh(); }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const params: Record<string, string> = { page: this.page().toString(), size: this.pageSize().toString() };
      if (this.search().trim()) params['search'] = this.search().trim();
      if (this.mediaOnly()) params['media_only'] = 'true';
      if (this.unlinkedOnly()) params['unlinked_only'] = 'true';
      if (this.hideCancelled()) params['hide_cancelled'] = 'true';
      const data = await firstValueFrom(this.http.get<{ rows: OrderRow[]; total: number; stats: { total: number; media: number; linked: number } }>(
        '/api/v2/admin/amazon-orders', { params }));
      this.rows.set(data.rows); this.total.set(data.total); this.stats.set(data.stats);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  async onSearch(event: Event): Promise<void> { this.search.set((event.target as HTMLInputElement).value); this.page.set(0); await this.refresh(); }
  async toggleCancelled(): Promise<void> { this.hideCancelled.update(v => !v); this.page.set(0); await this.refresh(); }
  async toggleMedia(): Promise<void> { this.mediaOnly.update(v => !v); this.page.set(0); await this.refresh(); }
  async toggleUnlinked(): Promise<void> { this.unlinkedOnly.update(v => !v); this.page.set(0); await this.refresh(); }
  async onPage(event: PageEvent): Promise<void> { this.page.set(event.pageIndex); this.pageSize.set(event.pageSize); await this.refresh(); }

  async uploadFile(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.uploading.set(true); this.uploadResult.set('');
    try {
      const bytes = await file.arrayBuffer();
      const result = await firstValueFrom(this.http.post<{ ok: boolean; inserted: number; skipped: number; total_parsed: number }>(
        '/api/v2/admin/amazon-orders/upload', bytes,
        { headers: { 'Content-Type': 'application/octet-stream', 'X-Filename': file.name } }
      ));
      this.uploadResult.set(`Imported ${result.inserted} orders (${result.skipped} duplicates skipped, ${result.total_parsed} parsed)`);
      await this.refresh();
    } catch { this.uploadResult.set('Upload failed'); }
    this.uploading.set(false);
    input.value = '';
  }

  // Link dialog
  openLink(order: OrderRow): void {
    this.linkOrder.set(order); this.linkQuery.set(''); this.linkResults.set([]); this.linkOpen.set(true);
  }
  closeLink(): void { this.linkOpen.set(false); this.linkOrder.set(null); }
  async searchItems(event: Event): Promise<void> {
    const q = (event.target as HTMLInputElement).value; this.linkQuery.set(q);
    if (q.trim().length < 2) { this.linkResults.set([]); return; }
    this.linkSearching.set(true);
    try { const d = await firstValueFrom(this.http.get<{ items: SearchItem[] }>('/api/v2/admin/amazon-orders/search-items', { params: { q: q.trim() } })); this.linkResults.set(d.items); }
    catch { /* ignore */ }
    this.linkSearching.set(false);
  }
  async linkTo(item: SearchItem): Promise<void> {
    const order = this.linkOrder(); if (!order) return;
    await firstValueFrom(this.http.post(`/api/v2/admin/amazon-orders/${order.id}/link/${item.id}`, {}));
    this.closeLink(); await this.refresh();
  }
  async unlink(order: OrderRow): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/amazon-orders/${order.id}/unlink`, {}));
    await this.refresh();
  }

  formatPrice(v: number | null): string { return v != null ? `$${v.toFixed(2)}` : '—'; }
}
