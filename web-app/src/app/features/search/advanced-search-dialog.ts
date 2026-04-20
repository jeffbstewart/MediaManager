import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AdvancedSearchPreset, AdvancedTrackSearchFilters, CatalogService } from '../../core/catalog.service';

/**
 * Advanced search popup. Three things:
 *   - dance preset chips pulled from the server (shared source of
 *     truth with iOS) that pre-fill the form on click;
 *   - BPM range + time-signature + text query fields;
 *   - Search button that closes the dialog with the current filters.
 *
 * The server side runs the actual query — this dialog only composes
 * the filter record. SearchComponent consumes the close result and
 * updates the URL so searches are bookmarkable.
 */
@Component({
  selector: 'app-advanced-search-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatChipsModule,
    MatDialogModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './advanced-search-dialog.html',
  styleUrl: './advanced-search-dialog.scss',
})
export class AdvancedSearchDialogComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  private readonly dialogRef = inject(MatDialogRef<AdvancedSearchDialogComponent, AdvancedTrackSearchFilters | null>);

  readonly presets = signal<AdvancedSearchPreset[]>([]);
  readonly loadingPresets = signal(true);

  // Form state
  readonly query = signal('');
  readonly bpmMin = signal<string>('');
  readonly bpmMax = signal<string>('');
  readonly timeSignature = signal<string>('');
  /** Highlighted preset key so the chip stays visibly selected after a click. */
  readonly activePresetKey = signal<string | null>(null);

  readonly timeSigOptions: { value: string; label: string }[] = [
    { value: '',    label: 'Any' },
    { value: '3/4', label: '3/4 (waltz)' },
    { value: '4/4', label: '4/4' },
    { value: '6/8', label: '6/8' },
  ];

  async ngOnInit(): Promise<void> {
    try {
      this.presets.set(await this.catalog.listAdvancedSearchPresets());
    } catch {
      this.presets.set([]);
    } finally {
      this.loadingPresets.set(false);
    }
  }

  applyPreset(p: AdvancedSearchPreset): void {
    this.bpmMin.set(p.bpm_min != null ? String(p.bpm_min) : '');
    this.bpmMax.set(p.bpm_max != null ? String(p.bpm_max) : '');
    this.timeSignature.set(p.time_signature ?? '');
    this.activePresetKey.set(p.key);
    // Don't auto-submit — user can tweak the text query or tighten
    // the BPM range before hitting Search.
  }

  clearForm(): void {
    this.query.set('');
    this.bpmMin.set('');
    this.bpmMax.set('');
    this.timeSignature.set('');
    this.activePresetKey.set(null);
  }

  onQueryInput(ev: Event): void { this.query.set((ev.target as HTMLInputElement).value); this.activePresetKey.set(null); }
  onBpmMinInput(ev: Event): void { this.bpmMin.set((ev.target as HTMLInputElement).value); this.activePresetKey.set(null); }
  onBpmMaxInput(ev: Event): void { this.bpmMax.set((ev.target as HTMLInputElement).value); this.activePresetKey.set(null); }
  onTimeSigChange(ev: Event): void { this.timeSignature.set((ev.target as HTMLSelectElement).value); this.activePresetKey.set(null); }

  submit(): void {
    const minNum = parseInt(this.bpmMin(), 10);
    const maxNum = parseInt(this.bpmMax(), 10);
    const filters: AdvancedTrackSearchFilters = {
      query: this.query().trim() || undefined,
      bpmMin: Number.isFinite(minNum) && minNum > 0 ? minNum : undefined,
      bpmMax: Number.isFinite(maxNum) && maxNum > 0 ? maxNum : undefined,
      timeSignature: this.timeSignature() || undefined,
    };
    // All fields blank would return nothing — surface that up front
    // rather than making the user wait for an empty result.
    if (!filters.query && filters.bpmMin == null && filters.bpmMax == null && !filters.timeSignature) {
      return;
    }
    this.dialogRef.close(filters);
  }

  cancel(): void { this.dialogRef.close(null); }
}
