import { Component, inject, signal, OnDestroy, ChangeDetectionStrategy, ElementRef, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { firstValueFrom } from 'rxjs';

interface Photo { id: string; url: string; captured_at: string | null; }

interface LookupResult {
  found: boolean; upc: string;
  media_item_id?: number; title_name?: string;
  media_format?: string; poster_url?: string | null;
  photos: Photo[];
}

interface SearchItem {
  media_item_id: number; upc: string | null;
  title_name: string; media_format: string; photo_count: number;
}

@Component({
  selector: 'app-document-ownership',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, MatButtonModule, MatCardModule],
  templateUrl: './document-ownership.html',
  styleUrl: './document-ownership.scss',
})
export class DocumentOwnershipComponent implements OnDestroy {
  private readonly http = inject(HttpClient);

  readonly videoRef = viewChild<ElementRef<HTMLVideoElement>>('videoEl');

  // Scan phase
  readonly phase = signal<'scan' | 'capture'>('scan');
  readonly upcInput = signal('');
  readonly searchQuery = signal('');
  readonly searchResults = signal<SearchItem[]>([]);
  readonly scanning = signal(false);
  readonly cameraActive = signal(false);
  readonly hasBarcodeApi = signal(typeof (window as any).BarcodeDetector !== 'undefined');

  // Capture phase
  readonly item = signal<LookupResult | null>(null);
  readonly uploading = signal(false);

  private cameraStream: MediaStream | null = null;
  private scanTimer: ReturnType<typeof setInterval> | null = null;
  private zxingControls: { stop: () => void } | null = null;

  ngOnDestroy(): void { this.stopCamera(); }

  // --- Scan Phase ---

  updateUpc(event: Event): void { this.upcInput.set((event.target as HTMLInputElement).value); }

  async lookupUpc(): Promise<void> {
    const upc = this.upcInput().trim();
    if (!upc) return;
    this.scanning.set(true);
    try {
      const result = await firstValueFrom(this.http.get<LookupResult>('/api/v2/admin/ownership/lookup', { params: { upc } }));
      this.item.set(result);
      this.phase.set('capture');
    } catch { /* ignore */ }
    this.scanning.set(false);
  }

  onUpcKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') this.lookupUpc();
  }

  async searchItems(event: Event): Promise<void> {
    const q = (event.target as HTMLInputElement).value;
    this.searchQuery.set(q);
    if (q.trim().length < 2) { this.searchResults.set([]); return; }
    try {
      const data = await firstValueFrom(this.http.get<{ items: SearchItem[] }>('/api/v2/admin/ownership/search', { params: { q: q.trim() } }));
      this.searchResults.set(data.items);
    } catch { /* ignore */ }
  }

  async selectItem(item: SearchItem): Promise<void> {
    if (item.upc) {
      this.upcInput.set(item.upc);
      await this.lookupUpc();
    }
  }

  // --- Camera Barcode Scanning ---

  async startCamera(): Promise<void> {
    try {
      this.cameraStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'environment', width: { ideal: 1280 }, height: { ideal: 720 } }
      });
      this.cameraActive.set(true);
      setTimeout(() => {
        const video = this.videoRef()?.nativeElement;
        if (video) { video.srcObject = this.cameraStream; video.play(); }
        this.startBarcodeDetection();
      }, 100);
    } catch { /* camera not available */ }
  }

  private async startBarcodeDetection(): Promise<void> {
    if (this.hasBarcodeApi()) {
      // Native BarcodeDetector (Chrome Android, Safari 18+)
      const detector = new (window as any).BarcodeDetector({ formats: ['upc_a', 'upc_e', 'ean_13', 'ean_8'] });
      this.scanTimer = setInterval(async () => {
        const video = this.videoRef()?.nativeElement;
        if (!video || video.readyState < 2) return;
        try {
          const barcodes = await detector.detect(video);
          if (barcodes.length > 0) {
            this.onBarcodeDetected(barcodes[0].rawValue);
          }
        } catch { /* ignore */ }
      }, 500);
    } else {
      // ZXing fallback (iOS Chrome, Firefox, etc.) — lazy-loaded, bundled at build time
      try {
        const { BrowserMultiFormatReader } = await import('@zxing/browser');
        const reader = new BrowserMultiFormatReader();
        const video = this.videoRef()?.nativeElement;
        if (!video) return;
        // decodeFromVideoElement uses a continuous callback
        const controls = await reader.decodeFromVideoElement(video, (result, error) => {
          if (result) {
            controls.stop();
            this.onBarcodeDetected(result.getText());
          }
          // error is normal when no barcode is in frame — ignore
        });
        // Store cleanup reference
        this.zxingControls = controls;
      } catch { /* ZXing load failed */ }
    }
  }

  private async onBarcodeDetected(code: string): Promise<void> {
    this.stopCamera();
    this.upcInput.set(code);
    await this.lookupUpc();
  }

  stopCamera(): void {
    if (this.scanTimer) { clearInterval(this.scanTimer); this.scanTimer = null; }
    if (this.zxingControls) { this.zxingControls.stop(); this.zxingControls = null; }
    this.cameraStream?.getTracks().forEach(t => t.stop());
    this.cameraStream = null;
    this.cameraActive.set(false);
  }

  // --- Capture Phase ---

  scanAnother(): void {
    this.phase.set('scan');
    this.item.set(null);
    this.upcInput.set('');
    this.searchQuery.set('');
    this.searchResults.set([]);
  }

  async onPhotoCapture(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.uploading.set(true);
    try {
      const bytes = await file.arrayBuffer();
      const headers: Record<string, string> = { 'Content-Type': file.type || 'image/jpeg' };
      const it = this.item();
      if (it?.media_item_id) headers['X-Media-Item-Id'] = it.media_item_id.toString();
      else if (it?.upc) headers['X-UPC'] = it.upc;
      await firstValueFrom(this.http.post('/api/v2/admin/ownership/upload', bytes, { headers }));
      // Refresh photos
      if (it?.upc) {
        const refreshed = await firstValueFrom(this.http.get<LookupResult>('/api/v2/admin/ownership/lookup', { params: { upc: it.upc } }));
        this.item.set(refreshed);
      }
    } catch { /* ignore */ }
    this.uploading.set(false);
    input.value = '';
  }

  async deletePhoto(photo: Photo): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v2/admin/ownership/photos/${photo.id}`));
    this.item.update(it => it ? { ...it, photos: it.photos.filter(p => p.id !== photo.id) } : it);
  }
}
