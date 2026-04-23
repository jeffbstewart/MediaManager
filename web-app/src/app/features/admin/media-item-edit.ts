import { Component, inject, signal, computed, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { AppRoutes } from '../../core/routes';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { firstValueFrom } from 'rxjs';
import { tmdbImageUrl } from '../../core/catalog.service';
import { CanDeactivateComponent } from '../../core/can-deactivate.guard';

interface TitleLink {
  join_id: number; title_id: number; title_name: string;
  media_type: string; tmdb_id: number | null; enrichment_status: string;
  poster_url: string | null; seasons: string | null;
}

interface BookAuthor { id: number; name: string; }
interface BookSeriesInfo { id: number; name: string; volume: string | null; }

interface MediaItemDetail {
  media_item_id: number; display_name: string; upc: string | null;
  product_name: string | null; media_format: string | null;
  /** Formats the admin is allowed to switch this item to — varies by media_type. */
  editable_formats: string[];
  media_type: string | null;
  storage_location: string | null;
  purchase_place: string | null; purchase_date: string | null;
  purchase_price: number | null; amazon_order_id: string | null;
  authors: BookAuthor[];
  book_series: BookSeriesInfo | null;
  titles: TitleLink[]; photo_count: number;
  photos: { id: string; captured_at: string | null }[];
}

interface TmdbResult {
  tmdb_id: number; title: string; media_type: string;
  release_year: number | null; poster_path: string | null; overview: string | null;
}

interface AmazonOrder {
  id: number; product_name: string; order_date: string | null; unit_price: number | null;
}

@Component({
  selector: 'app-media-item-edit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatProgressSpinnerModule, MatButtonModule, MatChipsModule],
  templateUrl: './media-item-edit.html',
  styleUrl: './media-item-edit.scss',
  // beforeunload covers tab close / refresh / external URL changes;
  // Angular router navigations are caught by canDeactivate below.
  host: { '(window:beforeunload)': 'onBeforeUnload($event)' },
})
export class MediaItemEditComponent implements OnInit, CanDeactivateComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly item = signal<MediaItemDetail | null>(null);
  readonly message = signal('');
  readonly messageType = signal<'success' | 'error'>('success');

  // TMDB search
  readonly tmdbQuery = signal('');
  readonly tmdbType = signal<'MOVIE' | 'TV'>('MOVIE');
  readonly tmdbResults = signal<TmdbResult[]>([]);
  readonly searching = signal(false);

  // Amazon orders
  readonly amazonQuery = signal('');
  readonly amazonOrders = signal<AmazonOrder[]>([]);

  // Purchase form. These are the only manually-saved fields on the
  // page — everything else (TMDB pick, format change, seasons, photos,
  // Amazon link) commits instantly on change. Only this block needs
  // dirty-state tracking.
  readonly purchasePlace = signal('');
  readonly purchaseDate = signal('');
  readonly purchasePrice = signal('');
  // Physical location on shelf / bookcase. Shown for any media, first
  // user of it is books.
  readonly storageLocation = signal('');

  // Pristine snapshot, refreshed on load and after each successful save;
  // dirty = any current field differs from the snapshot.
  private readonly pristine = signal({ place: '', date: '', price: '', location: '' });
  readonly isDirty = computed(() => {
    const p = this.pristine();
    return this.purchasePlace() !== p.place
      || this.purchaseDate() !== p.date
      || this.purchasePrice() !== p.price
      || this.storageLocation() !== p.location;
  });

  private itemId = 0;

  async ngOnInit(): Promise<void> {
    this.itemId = Number(this.route.snapshot.paramMap.get('mediaItemId'));
    await this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const data = await firstValueFrom(this.http.get<MediaItemDetail>(`/api/v2/admin/media-item/${this.itemId}`));
      this.item.set(data);

      // Init purchase form + pristine snapshot
      const place = data.purchase_place ?? '';
      const date = data.purchase_date ?? '';
      const price = data.purchase_price?.toString() ?? '';
      const location = data.storage_location ?? '';
      this.purchasePlace.set(place);
      this.purchaseDate.set(date);
      this.purchasePrice.set(price);
      this.storageLocation.set(location);
      this.pristine.set({ place, date, price, location });

      // Init TMDB search with title name
      const primary = data.titles[0];
      if (primary) {
        this.tmdbQuery.set(primary.title_name);
        this.tmdbType.set(primary.media_type === 'TV' ? 'TV' : 'MOVIE');
      }

      // Load Amazon orders
      await this.refreshAmazon();
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  get primary(): TitleLink | null { return this.item()?.titles[0] ?? null; }

  get needsTmdbFix(): boolean {
    const s = this.primary?.enrichment_status;
    return s === 'FAILED' || s === 'SKIPPED' || s === 'ABANDONED';
  }

  get isPending(): boolean {
    const s = this.primary?.enrichment_status;
    return s === 'PENDING' || s === 'REASSIGNMENT_REQUESTED';
  }

  formatLabel(f: string | null): string {
    switch (f) {
      case 'BLURAY': return 'Blu-ray';
      case 'UHD_BLURAY': return 'UHD Blu-ray';
      case 'HD_DVD': return 'HD DVD';
      case 'MASS_MARKET_PAPERBACK': return 'Paperback (mass market)';
      case 'TRADE_PAPERBACK': return 'Paperback (trade)';
      case 'HARDBACK': return 'Hardcover';
      case 'EBOOK_EPUB': return 'eBook (EPUB)';
      case 'EBOOK_PDF': return 'eBook (PDF)';
      case 'AUDIOBOOK_CD': return 'Audiobook (CD)';
      case 'AUDIOBOOK_DIGITAL': return 'Audiobook (digital)';
      case 'CD': return 'CD';
      case 'DIGITAL_MUSIC_ALBUM': return 'Digital album';
      default: return f ?? '';
    }
  }

  /** Media type switching only makes sense for video/personal items. Book and album items were disambiguated at scan time (ISBN / MB release MBID) and changing them would orphan authors / track rows. */
  get showMediaTypeSelector(): boolean {
    const mt = this.primary?.media_type;
    return mt === 'MOVIE' || mt === 'TV' || mt === 'PERSONAL';
  }

  get isAlbum(): boolean { return this.primary?.media_type === 'ALBUM'; }

  statusLabel(s: string): string {
    switch (s) {
      case 'FAILED': return 'Enrichment failed';
      case 'SKIPPED': return 'No TMDB match found';
      case 'ABANDONED': return 'Enrichment abandoned';
      case 'PENDING': return 'Awaiting enrichment...';
      case 'REASSIGNMENT_REQUESTED': return 'Re-enrichment queued...';
      default: return s;
    }
  }

  // --- Media Type ---
  async setMediaType(event: Event): Promise<void> {
    const value = (event.target as HTMLSelectElement).value;
    await firstValueFrom(this.http.post(`/api/v2/admin/media-item/${this.itemId}/media-type`, { media_type: value }));
    this.flash('Media type updated');
    await this.refresh();
  }

  // --- Media Format ---
  /**
   * Change the physical/digital format of this item (e.g. book scanned
   * as paperback but it's actually hardcover). Server validates that
   * the new format is compatible with the title's media_type.
   */
  async setMediaFormat(event: Event): Promise<void> {
    const value = (event.target as HTMLSelectElement).value;
    if (!value || value === this.item()?.media_format) return;
    try {
      await firstValueFrom(this.http.post(
        `/api/v2/admin/media-item/${this.itemId}/format`,
        { media_format: value }
      ));
      this.flash('Media format updated — replacement price cleared, will re-price on next cycle');
      await this.refresh();
    } catch (e: unknown) {
      const msg = (e as { error?: { error?: string } })?.error?.error ?? 'Failed to update format';
      this.flash(msg, 'error');
    }
  }

  // --- Seasons ---
  async saveSeasons(joinId: number, event: Event): Promise<void> {
    const value = (event.target as HTMLInputElement).value;
    try {
      await firstValueFrom(this.http.post(`/api/v2/admin/media-item/${this.itemId}/seasons`, { join_id: joinId, seasons: value }));
      this.flash('Seasons updated');
    } catch (e: unknown) {
      const msg = (e as { error?: { error?: string } })?.error?.error ?? 'Invalid format';
      this.flash(msg, 'error');
    }
  }

  // --- TMDB Search ---
  updateTmdbQuery(event: Event): void { this.tmdbQuery.set((event.target as HTMLInputElement).value); }

  async searchTmdb(): Promise<void> {
    const q = this.tmdbQuery().trim();
    if (!q) return;
    this.searching.set(true);
    try {
      const d = await firstValueFrom(this.http.get<{ results: TmdbResult[] }>(
        '/api/v2/admin/media-item/search-tmdb', { params: { q, type: this.tmdbType() } }));
      this.tmdbResults.set(d.results);
    } catch { /* ignore */ }
    this.searching.set(false);
  }

  async selectTmdb(result: TmdbResult): Promise<void> {
    try {
      const r = await firstValueFrom(this.http.post<{ status: string; title_name?: string }>(
        `/api/v2/admin/media-item/${this.itemId}/assign-tmdb`,
        { tmdb_id: result.tmdb_id, media_type: result.media_type }));
      if (r.status === 'merged') {
        this.flash(`Merged into existing title: ${r.title_name}`);
      } else {
        this.flash('TMDB match set — re-enrichment queued');
      }
      this.tmdbResults.set([]);
      await this.refresh();
    } catch (e: unknown) {
      const msg = (e as { error?: { error?: string } })?.error?.error ?? 'Failed';
      this.flash(msg, 'error');
    }
  }

  posterUrl(path: string): string { return tmdbImageUrl(path, 'w92')!; }

  // --- Purchase Info ---
  async savePurchase(): Promise<void> {
    const body: Record<string, unknown> = {
      purchase_place: this.purchasePlace() || null,
      purchase_date: this.purchaseDate() || null,
      purchase_price: this.purchasePrice() ? parseFloat(this.purchasePrice()) : null,
      storage_location: this.storageLocation() || null,
    };
    await firstValueFrom(this.http.post(`/api/v2/admin/media-item/${this.itemId}/purchase`, body));
    // Re-baseline so the form reads as clean again.
    this.pristine.set({
      place: this.purchasePlace(),
      date: this.purchaseDate(),
      price: this.purchasePrice(),
      location: this.storageLocation(),
    });
    this.flash('Purchase info saved');
  }

  /**
   * Router CanDeactivate hook. When the purchase form has unsaved
   * edits, prompt before allowing navigation. Clean returns true.
   */
  canDeactivate(): boolean {
    if (!this.isDirty()) return true;
    return confirm('You have unsaved purchase-info changes. Leave without saving?');
  }

  /**
   * Tab close / refresh / external URL handler — Angular's router
   * guard only catches in-app navigation. Setting `returnValue` arms
   * the browser's native "Leave site?" dialog.
   */
  onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.isDirty()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }

  updatePurchasePlace(event: Event): void { this.purchasePlace.set((event.target as HTMLInputElement).value); }
  updatePurchaseDate(event: Event): void { this.purchaseDate.set((event.target as HTMLInputElement).value); }
  updatePurchasePrice(event: Event): void { this.purchasePrice.set((event.target as HTMLInputElement).value); }
  updateStorageLocation(event: Event): void { this.storageLocation.set((event.target as HTMLInputElement).value); }

  get isBook(): boolean { return this.item()?.media_type === 'BOOK'; }

  // --- Amazon Orders ---
  updateAmazonQuery(event: Event): void { this.amazonQuery.set((event.target as HTMLInputElement).value); }

  async refreshAmazon(): Promise<void> {
    try {
      const d = await firstValueFrom(this.http.get<{ orders: AmazonOrder[]; search_query: string }>(
        `/api/v2/admin/media-item/${this.itemId}/amazon-orders`,
        { params: this.amazonQuery() ? { q: this.amazonQuery() } : {} }));
      this.amazonOrders.set(d.orders);
      if (!this.amazonQuery()) this.amazonQuery.set(d.search_query);
    } catch { /* ignore */ }
  }

  async searchAmazon(): Promise<void> { await this.refreshAmazon(); }

  async linkAmazon(order: AmazonOrder): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/media-item/${this.itemId}/link-amazon/${order.id}`, {}));
    this.flash('Linked to Amazon order');
    await this.refresh();
  }

  // --- Ownership Photos ---

  /** Triggered by the hidden file input when the admin picks one or more files. */
  async onPhotoFilesPicked(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const files = input.files;
    if (!files || files.length === 0) return;
    try {
      for (let i = 0; i < files.length; i++) {
        await this.uploadPhoto(files.item(i)!);
      }
      this.flash(`Uploaded ${files.length} photo${files.length === 1 ? '' : 's'}`);
      await this.refresh();
    } catch (e) {
      this.flash(`Photo upload failed: ${(e as Error).message ?? e}`, 'error');
    } finally {
      // Reset so the same filename can be picked again after a failed try.
      input.value = '';
    }
  }

  private async uploadPhoto(file: File): Promise<void> {
    // The server expects raw image bytes with the media-item id in a
    // custom header — matches the scanner's upload path so it needed
    // no new endpoint for this button.
    const headers = {
      'Content-Type': file.type || 'image/jpeg',
      'X-Media-Item-Id': String(this.itemId),
    };
    await firstValueFrom(
      this.http.post('/api/v2/admin/ownership/upload', file, { headers })
    );
  }

  async deletePhoto(photoId: string): Promise<void> {
    if (!window.confirm('Delete this photo?')) return;
    try {
      await firstValueFrom(this.http.delete(`/api/v2/admin/ownership/photos/${photoId}`));
      this.flash('Photo deleted');
      await this.refresh();
    } catch (e) {
      this.flash(`Delete failed: ${(e as Error).message ?? e}`, 'error');
    }
  }

  // --- Helpers ---
  private flash(msg: string, type: 'success' | 'error' = 'success'): void {
    this.message.set(msg);
    this.messageType.set(type);
    setTimeout(() => this.message.set(''), 3000);
  }
}
