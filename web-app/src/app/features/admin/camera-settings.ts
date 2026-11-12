import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { firstValueFrom } from 'rxjs';

interface CameraRow { id: number; name: string; go2rtc_name: string; enabled: boolean; display_order: number; }

@Component({
  selector: 'app-camera-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DragDropModule, MatIconModule, MatProgressSpinnerModule, MatButtonModule, MatCardModule],
  templateUrl: './camera-settings.html',
  styleUrl: './camera-settings.scss',
})
export class CameraSettingsComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly cameras = signal<CameraRow[]>([]);
  readonly go2rtcStatus = signal('unknown');

  // Add/edit dialog
  readonly dialogOpen = signal(false);
  readonly dialogMode = signal<'add' | 'edit'>('add');
  readonly editingId = signal<number | null>(null);
  readonly dialogName = signal('');
  readonly dialogRtsp = signal('');
  readonly dialogSnapshot = signal('');
  readonly dialogError = signal('');

  async ngOnInit(): Promise<void> { await this.refresh(); }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const data = await firstValueFrom(this.http.get<{ cameras: CameraRow[]; go2rtc_status: string }>('/api/v2/admin/cameras'));
      this.cameras.set(data.cameras);
      this.go2rtcStatus.set(data.go2rtc_status);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  async onDrop(event: CdkDragDrop<CameraRow[]>): Promise<void> {
    const list = [...this.cameras()];
    moveItemInArray(list, event.previousIndex, event.currentIndex);
    this.cameras.set(list);
    const ids = list.map(c => c.id);
    await firstValueFrom(this.http.post('/api/v2/admin/cameras/reorder', { ids }));
  }

  async toggleEnabled(cam: CameraRow): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/cameras/${cam.id}`, { enabled: !cam.enabled }));
    cam.enabled = !cam.enabled;
    this.cameras.update(c => [...c]);
  }

  async deleteCam(cam: CameraRow): Promise<void> {
    if (!confirm(`Delete camera "${cam.name}"?`)) return;
    await firstValueFrom(this.http.delete(`/api/v2/admin/cameras/${cam.id}`));
    await this.refresh();
  }

  openAdd(): void {
    this.dialogMode.set('add');
    this.editingId.set(null);
    this.dialogName.set('');
    this.dialogRtsp.set('');
    this.dialogSnapshot.set('');
    this.dialogError.set('');
    this.dialogOpen.set(true);
  }

  openEdit(cam: CameraRow): void {
    this.dialogMode.set('edit');
    this.editingId.set(cam.id);
    this.dialogName.set(cam.name);
    this.dialogRtsp.set(''); // Don't prefill credentials
    this.dialogSnapshot.set('');
    this.dialogError.set('');
    this.dialogOpen.set(true);
  }

  closeDialog(): void { this.dialogOpen.set(false); }

  updateField(field: string, event: Event): void {
    const v = (event.target as HTMLInputElement).value;
    if (field === 'name') this.dialogName.set(v);
    else if (field === 'rtsp') this.dialogRtsp.set(v);
    else if (field === 'snapshot') this.dialogSnapshot.set(v);
  }

  async saveDialog(): Promise<void> {
    const name = this.dialogName().trim();
    if (!name) { this.dialogError.set('Name is required'); return; }
    this.dialogError.set('');

    try {
      if (this.dialogMode() === 'edit') {
        const body: Record<string, unknown> = { name };
        if (this.dialogRtsp().trim()) body['rtsp_url'] = this.dialogRtsp().trim();
        if (this.dialogSnapshot().trim()) body['snapshot_url'] = this.dialogSnapshot().trim();
        await firstValueFrom(this.http.post(`/api/v2/admin/cameras/${this.editingId()}`, body));
      } else {
        if (!this.dialogRtsp().trim()) { this.dialogError.set('RTSP URL is required'); return; }
        await firstValueFrom(this.http.post('/api/v2/admin/cameras', {
          name, rtsp_url: this.dialogRtsp().trim(), snapshot_url: this.dialogSnapshot().trim()
        }));
      }
      this.closeDialog();
      await this.refresh();
    } catch { this.dialogError.set('Request failed'); }
  }

  // Snapshot preview
  readonly snapshotOpen = signal(false);
  readonly snapshotName = signal('');
  readonly snapshotUrl = signal('');
  readonly snapshotError = signal(false);

  testSnapshot(cam: CameraRow): void {
    this.snapshotName.set(cam.name);
    this.snapshotError.set(false);
    this.snapshotUrl.set(`/api/v2/cameras/${cam.id}/snapshot?t=${Date.now()}`);
    this.snapshotOpen.set(true);
  }

  refreshSnapshot(): void {
    this.snapshotError.set(false);
    const base = this.snapshotUrl().replace(/\?.*/, '');
    this.snapshotUrl.set(`${base}?t=${Date.now()}`);
  }

  async duplicateCam(cam: CameraRow): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/cameras/${cam.id}/duplicate`, {}));
    await this.refresh();
  }

  statusLabel(status: string): string {
    switch (status) {
      case 'running': return 'go2rtc: Running';
      case 'unhealthy': return 'go2rtc: Process alive but API not responding';
      case 'stopped': return 'go2rtc: Not running';
      case 'not_configured': return 'go2rtc: Not configured';
      default: return 'go2rtc: Unknown';
    }
  }

  statusColor(status: string): string {
    // Solid-pill backgrounds: paired with white text in the template.
    // Picked dark enough to clear AA against white at default size.
    return status === 'running' ? '#2e7d32' : '#c62828';
  }
}
