import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DecimalPipe, CurrencyPipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { firstValueFrom } from 'rxjs';

interface ValuationRow {
  id: number;
  display_name: string;
  media_format: string;
  upc: string | null;
  purchase_place: string | null;
  purchase_date: string | null;
  purchase_price: number | null;
  replacement_value: number | null;
  override_asin: string | null;
  photo_count: number;
}

interface ValuationSummary {
  total_items: number;
  total_purchase: number;
  total_replacement: number;
}

@Component({
  selector: 'app-valuation',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DecimalPipe, CurrencyPipe, MatIconModule, MatProgressSpinnerModule, MatTableModule, MatPaginatorModule, MatButtonModule, MatCardModule, MatChipsModule],
  templateUrl: './valuation.html',
  styleUrl: './valuation.scss',
})
export class ValuationComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly rows = signal<ValuationRow[]>([]);
  readonly total = signal(0);
  readonly summary = signal<ValuationSummary>({ total_items: 0, total_purchase: 0, total_replacement: 0 });
  readonly search = signal('');
  readonly unpricedOnly = signal(false);
  readonly page = signal(0);
  readonly pageSize = signal(50);
  readonly columns = ['name', 'format', 'place', 'date', 'price', 'replacement', 'photos', 'actions'];

  // Edit dialog
  readonly editOpen = signal(false);
  readonly editItem = signal<ValuationRow | null>(null);
  readonly editPlace = signal('');
  readonly editDate = signal('');
  readonly editPrice = signal('');
  readonly editReplacement = signal('');
  readonly editAsin = signal('');

  async ngOnInit(): Promise<void> { await this.refresh(); }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const params: Record<string, string> = { page: this.page().toString(), size: this.pageSize().toString() };
      if (this.search().trim()) params['search'] = this.search().trim();
      if (this.unpricedOnly()) params['unpriced_only'] = 'true';
      const data = await firstValueFrom(this.http.get<{ rows: ValuationRow[]; total: number; summary: ValuationSummary }>(
        '/api/v2/admin/valuations', { params }));
      this.rows.set(data.rows);
      this.total.set(data.total);
      this.summary.set(data.summary);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  async onSearch(event: Event): Promise<void> {
    this.search.set((event.target as HTMLInputElement).value);
    this.page.set(0);
    await this.refresh();
  }

  async toggleUnpriced(): Promise<void> {
    this.unpricedOnly.update(v => !v);
    this.page.set(0);
    await this.refresh();
  }

  async onPage(event: PageEvent): Promise<void> {
    this.page.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
    await this.refresh();
  }

  openEdit(row: ValuationRow): void {
    this.editItem.set(row);
    this.editPlace.set(row.purchase_place ?? '');
    this.editDate.set(row.purchase_date ?? '');
    this.editPrice.set(row.purchase_price?.toString() ?? '');
    this.editReplacement.set(row.replacement_value?.toString() ?? '');
    this.editAsin.set(row.override_asin ?? '');
    this.editOpen.set(true);
  }

  closeEdit(): void { this.editOpen.set(false); }

  updateField(field: string, event: Event): void {
    const v = (event.target as HTMLInputElement).value;
    switch (field) {
      case 'place': this.editPlace.set(v); break;
      case 'date': this.editDate.set(v); break;
      case 'price': this.editPrice.set(v); break;
      case 'replacement': this.editReplacement.set(v); break;
      case 'asin': this.editAsin.set(v); break;
    }
  }

  async saveEdit(): Promise<void> {
    const item = this.editItem();
    if (!item) return;
    const body: Record<string, unknown> = {};
    body['purchase_place'] = this.editPlace();
    body['purchase_date'] = this.editDate();
    const price = parseFloat(this.editPrice());
    if (!isNaN(price)) body['purchase_price'] = price; else body['purchase_price'] = null;
    const repl = parseFloat(this.editReplacement());
    if (!isNaN(repl)) body['replacement_value'] = repl; else body['replacement_value'] = null;
    body['override_asin'] = this.editAsin();
    await firstValueFrom(this.http.post(`/api/v2/admin/valuations/${item.id}`, body));
    this.closeEdit();
    await this.refresh();
  }

  formatPrice(v: number | null): string {
    return v != null ? `$${v.toFixed(2)}` : '—';
  }
}
