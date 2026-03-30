import { Component, inject, signal, OnInit, OnDestroy, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { firstValueFrom } from 'rxjs';

@Component({
  selector: 'app-inventory-report',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, MatProgressBarModule, MatButtonModule, MatCardModule],
  template: `
    <div class="report-page">
      <mat-card class="report-card">
        <mat-card-header>
          <mat-card-title>Insurance Inventory Report</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <p class="desc">Generate a report of all media purchases for insurance documentation.</p>

          <label class="checkbox-row">
            <input type="checkbox" [checked]="includePhotos()"
                   [disabled]="photoCount() === 0 || generating()"
                   (change)="includePhotos.set($any($event.target).checked)" />
            <span>
              @if (photoCount() > 0) {
                Include ownership photos in PDF ({{ photoCount() }} photos across {{ itemsWithPhotos() }} items)
              } @else {
                Include ownership photos in PDF (no photos captured yet)
              }
            </span>
          </label>

          @if (generating()) {
            <div class="progress-section">
              @if (progressTotal() > 1) {
                <mat-progress-bar mode="determinate" [value]="(progressCurrent() / progressTotal()) * 100" />
              } @else {
                <mat-progress-bar mode="indeterminate" />
              }
              <span class="progress-label">{{ progressPhase() }}{{ progressTotal() > 1 ? ' (' + progressCurrent() + ' / ' + progressTotal() + ')' : '' }}</span>
            </div>
          }

          @if (downloadReady()) {
            <div class="download-section">
              <button mat-flat-button color="accent" (click)="downloadResult()">
                <mat-icon>download</mat-icon> Download {{ downloadType() }}
              </button>
            </div>
          }

          @if (errorMsg()) {
            <p class="error-text">{{ errorMsg() }}</p>
          }

          <div class="button-row">
            <button mat-flat-button color="primary" (click)="startGenerate('pdf')" [disabled]="generating()">
              <mat-icon>picture_as_pdf</mat-icon> Generate PDF
            </button>
            <button mat-flat-button color="primary" (click)="startGenerate('csv')" [disabled]="generating()">
              <mat-icon>table_chart</mat-icon> Generate CSV
            </button>
          </div>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: `
    .report-page { padding: 1.5rem; max-width: 600px; margin: 0 auto; }
    .report-card { text-align: center; }
    .desc { opacity: 0.6; font-size: 0.875rem; margin-bottom: 1rem; }
    .checkbox-row {
      display: flex; align-items: center; gap: 0.5rem;
      justify-content: center; font-size: 0.875rem;
      margin-bottom: 1.5rem; cursor: pointer;
      input { accent-color: var(--mat-sys-primary, #bb86fc); width: 16px; height: 16px; }
    }
    .progress-section { margin-bottom: 1rem; }
    .progress-label { font-size: 0.8125rem; opacity: 0.5; display: block; margin-top: 0.25rem; }
    .download-section { margin-bottom: 1rem; }
    .error-text { color: #f44336; font-size: 0.875rem; }
    .button-row { display: flex; justify-content: center; gap: 1rem; }
  `,
})
export class InventoryReportComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);

  readonly photoCount = signal(0);
  readonly itemsWithPhotos = signal(0);
  readonly includePhotos = signal(false);
  readonly generating = signal(false);
  readonly downloadReady = signal(false);
  readonly downloadType = signal('');
  readonly progressPhase = signal('');
  readonly progressCurrent = signal(0);
  readonly progressTotal = signal(0);
  readonly errorMsg = signal('');

  private pollTimer: ReturnType<typeof setInterval> | null = null;

  async ngOnInit(): Promise<void> {
    try {
      const info = await firstValueFrom(this.http.get<{ photo_count: number; items_with_photos: number }>('/api/v2/admin/report/info'));
      this.photoCount.set(info.photo_count);
      this.itemsWithPhotos.set(info.items_with_photos);
    } catch { /* ignore */ }
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  async startGenerate(type: 'csv' | 'pdf'): Promise<void> {
    this.generating.set(true);
    this.downloadReady.set(false);
    this.errorMsg.set('');
    this.progressPhase.set('Starting...');
    this.progressCurrent.set(0);
    this.progressTotal.set(0);
    this.downloadType.set(type.toUpperCase());

    const url = type === 'csv'
      ? '/api/v2/admin/report/csv/start'
      : `/api/v2/admin/report/pdf/start?photos=${this.includePhotos()}`;
    await firstValueFrom(this.http.post(url, {}));
    this.pollTimer = setInterval(() => this.pollStatus(), 1000);
  }

  private async pollStatus(): Promise<void> {
    try {
      const s = await firstValueFrom(this.http.get<{
        status: string; phase: string; current: number; total: number; error: string | null;
      }>('/api/v2/admin/report/status'));

      this.progressPhase.set(s.phase);
      this.progressCurrent.set(s.current);
      this.progressTotal.set(s.total);

      if (s.status === 'complete') {
        this.stopPolling();
        this.generating.set(false);
        this.downloadReady.set(true);
      } else if (s.status === 'error') {
        this.stopPolling();
        this.generating.set(false);
        this.errorMsg.set(s.error ?? 'Generation failed');
      }
    } catch { /* ignore poll errors */ }
  }

  downloadResult(): void {
    window.open('/api/v2/admin/report/download', '_blank');
    this.downloadReady.set(false);
  }

  private stopPolling(): void {
    if (this.pollTimer) { clearInterval(this.pollTimer); this.pollTimer = null; }
  }
}
