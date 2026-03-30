import { Component, inject, signal, OnInit, OnDestroy, ChangeDetectionStrategy, ElementRef, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatMenuModule } from '@angular/material/menu';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

interface RecentItem {
  type: string; media_item_id?: number; scan_id?: number; title_id?: number;
  display_name: string; upc: string | null; format: string | null;
  enrichment_status: string; poster_url: string | null;
  has_purchase: boolean; photo_count: number; entry_source: string; created_at: string | null;
}

interface TmdbResult { tmdb_id: number; title: string; media_type: string; release_year: number | null; poster_path: string | null; overview: string | null; }

@Component({
  selector: 'app-add-item',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatProgressSpinnerModule, MatTabsModule, MatTableModule, MatButtonModule, MatCardModule, MatChipsModule, MatMenuModule],
  templateUrl: './add-item.html',
  styleUrl: './add-item.scss',
})
export class AddItemComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);

  readonly videoRef = viewChild<ElementRef<HTMLVideoElement>>('videoEl');

  // Scan tab
  readonly upcInput = signal('');
  readonly scanMessage = signal('');
  readonly scanSuccess = signal(false);
  readonly quotaUsed = signal(0);
  readonly quotaLimit = signal(100);
  readonly cameraActive = signal(false);
  readonly hasBarcodeApi = signal(typeof (window as any).BarcodeDetector !== 'undefined');
  private cameraStream: MediaStream | null = null;
  private scanTimer: ReturnType<typeof setInterval> | null = null;
  private zxingControls: { stop: () => void } | null = null;

  // Search tab
  readonly searchQuery = signal('');
  readonly searchType = signal<'MOVIE' | 'TV'>('MOVIE');
  readonly searchResults = signal<TmdbResult[]>([]);
  readonly searching = signal(false);
  readonly addFormat = signal('BLURAY');
  readonly addSeasons = signal('');

  // Recent items
  readonly items = signal<RecentItem[]>([]);
  readonly itemFilter = signal('ALL');
  readonly refreshTimer = signal<ReturnType<typeof setInterval> | null>(null);
  readonly recentColumns = ['poster', 'title', 'format', 'status', 'source', 'actions'];

  readonly filterOptions = [
    { value: 'ALL', label: 'All Recent' },
    { value: 'NEEDS_ATTENTION', label: 'Needs Attention' },
    { value: 'UPC_NOT_FOUND', label: 'UPC Not Found' },
    { value: 'NEEDS_ENRICHMENT', label: 'Needs Enrichment' },
    { value: 'NEEDS_PURCHASE', label: 'Needs Purchase' },
    { value: 'NEEDS_PHOTOS', label: 'Needs Photos' },
  ];

  readonly formatOptions = [
    { value: 'DVD', label: 'DVD' },
    { value: 'BLURAY', label: 'Blu-ray' },
    { value: 'UHD_BLURAY', label: 'UHD Blu-ray' },
    { value: 'HD_DVD', label: 'HD DVD' },
  ];

  async ngOnInit(): Promise<void> {
    await this.refreshQuota();
    await this.refreshItems();
    const timer = setInterval(() => this.refreshItems(), 10000);
    this.refreshTimer.set(timer);
  }

  ngOnDestroy(): void {
    const t = this.refreshTimer();
    if (t) clearInterval(t);
    this.stopCamera();
  }

  // --- Scan Tab ---

  updateUpc(event: Event): void { this.upcInput.set((event.target as HTMLInputElement).value); }
  onUpcKeydown(event: KeyboardEvent): void { if (event.key === 'Enter') this.submitUpc(); }

  async submitUpc(): Promise<void> {
    const upc = this.upcInput().trim();
    if (!upc) return;
    try {
      const r = await firstValueFrom(this.http.post<{ status: string; upc?: string; title_name?: string; reason?: string }>(
        '/api/v2/admin/add-item/scan', { upc }));
      if (r.status === 'created') { this.scanMessage.set(`Scanned: ${r.upc}`); this.scanSuccess.set(true); }
      else if (r.status === 'duplicate') { this.scanMessage.set(`Already scanned: ${r.title_name}`); this.scanSuccess.set(false); }
      else { this.scanMessage.set(r.reason ?? 'Invalid'); this.scanSuccess.set(false); }
      await this.refreshItems();
      await this.refreshQuota();
    } catch { this.scanMessage.set('Request failed'); this.scanSuccess.set(false); }
    this.upcInput.set('');
  }

  async refreshQuota(): Promise<void> {
    try {
      const q = await firstValueFrom(this.http.get<{ used: number; limit: number }>('/api/v2/admin/add-item/quota'));
      this.quotaUsed.set(q.used); this.quotaLimit.set(q.limit);
    } catch { /* ignore */ }
  }

  // Camera
  async startCamera(): Promise<void> {
    try {
      this.cameraStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment', width: { ideal: 1280 }, height: { ideal: 720 } } });
      this.cameraActive.set(true);
      setTimeout(() => { const v = this.videoRef()?.nativeElement; if (v) { v.srcObject = this.cameraStream; v.play(); } this.startBarcodeDetection(); }, 100);
    } catch { /* camera not available */ }
  }

  private async startBarcodeDetection(): Promise<void> {
    if (this.hasBarcodeApi()) {
      const detector = new (window as any).BarcodeDetector({ formats: ['upc_a', 'upc_e', 'ean_13', 'ean_8'] });
      this.scanTimer = setInterval(async () => {
        const v = this.videoRef()?.nativeElement; if (!v || v.readyState < 2) return;
        try { const b = await detector.detect(v); if (b.length > 0) { this.stopCamera(); this.upcInput.set(b[0].rawValue); await this.submitUpc(); } } catch { /* ignore */ }
      }, 500);
    } else {
      try {
        const { BrowserMultiFormatReader } = await import('@zxing/browser');
        const reader = new BrowserMultiFormatReader();
        const v = this.videoRef()?.nativeElement; if (!v) return;
        const controls = await reader.decodeFromVideoElement(v, (result) => {
          if (result) { controls.stop(); this.stopCamera(); this.upcInput.set(result.getText()); this.submitUpc(); }
        });
        this.zxingControls = controls;
      } catch { /* ZXing failed */ }
    }
  }

  stopCamera(): void {
    if (this.scanTimer) { clearInterval(this.scanTimer); this.scanTimer = null; }
    if (this.zxingControls) { this.zxingControls.stop(); this.zxingControls = null; }
    this.cameraStream?.getTracks().forEach(t => t.stop());
    this.cameraStream = null;
    this.cameraActive.set(false);
  }

  // --- Search Tab ---

  async searchTmdb(event?: Event): Promise<void> {
    if (event) this.searchQuery.set((event.target as HTMLInputElement).value);
    const q = this.searchQuery().trim();
    if (q.length < 2) { this.searchResults.set([]); return; }
    this.searching.set(true);
    try {
      const d = await firstValueFrom(this.http.get<{ results: TmdbResult[] }>('/api/v2/admin/add-item/search-tmdb', { params: { q, type: this.searchType() } }));
      this.searchResults.set(d.results);
    } catch { /* ignore */ }
    this.searching.set(false);
  }

  async addFromTmdb(result: TmdbResult): Promise<void> {
    await firstValueFrom(this.http.post('/api/v2/admin/add-item/add-from-tmdb', {
      tmdb_id: result.tmdb_id, media_type: result.media_type, format: this.addFormat(),
      seasons: this.searchType() === 'TV' ? this.addSeasons() : null
    }));
    this.scanMessage.set(`Added: ${result.title}`); this.scanSuccess.set(true);
    this.searchResults.set([]);
    await this.refreshItems();
  }

  // --- Recent Items ---

  async refreshItems(): Promise<void> {
    try {
      const d = await firstValueFrom(this.http.get<{ items: RecentItem[] }>('/api/v2/admin/add-item/recent', { params: { filter: this.itemFilter() } }));
      this.items.set(d.items);
    } catch { /* ignore */ }
  }

  async setFilter(f: string): Promise<void> { this.itemFilter.set(f); await this.refreshItems(); }

  async deleteEntry(item: RecentItem): Promise<void> {
    const label = item.display_name || item.upc || 'this entry';
    if (!confirm(`Delete "${label}"? This will remove the media item and any orphaned title data. This cannot be undone.`)) return;

    if (item.type === 'scan' && item.scan_id) {
      await firstValueFrom(this.http.delete(`/api/v2/admin/add-item/scan/${item.scan_id}`));
    } else if (item.type === 'item' && item.media_item_id) {
      await firstValueFrom(this.http.delete(`/api/v2/admin/add-item/item/${item.media_item_id}`));
    }
    await this.refreshItems();
  }

  statusLabel(s: string): string {
    switch (s) { case 'ENRICHED': return 'Ready'; case 'FAILED': return 'Failed'; case 'PENDING': return 'Enriching';
      case 'NOT_LOOKED_UP': return 'Queued'; case 'NOT_FOUND': return 'UPC Not Found';
      case 'REASSIGNMENT_REQUESTED': return 'Enriching'; default: return s; }
  }

  statusColor(s: string): string {
    switch (s) { case 'ENRICHED': return '#4caf50'; case 'FAILED': case 'NOT_FOUND': return '#f44336';
      case 'PENDING': case 'REASSIGNMENT_REQUESTED': case 'NOT_LOOKED_UP': return 'var(--mat-sys-primary, #bb86fc)'; default: return 'rgba(255,255,255,0.5)'; }
  }

  posterUrl(path: string): string { return `https://image.tmdb.org/t/p/w92${path}`; }
}
