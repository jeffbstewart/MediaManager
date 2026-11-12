import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { firstValueFrom } from 'rxjs';

interface Tuner { id: number; name: string; device_id: string; ip_address: string; model_number: string; tuner_count: number; firmware_version: string; enabled: boolean; }
interface Channel { id: number; tuner_id: number; guide_number: string; guide_name: string; network_affiliation: string | null; reception_quality: number; enabled: boolean; }

@Component({
  selector: 'app-live-tv-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, MatTableModule, MatButtonModule, MatCardModule],
  templateUrl: './live-tv-settings.html',
  styleUrl: './live-tv-settings.scss',
})
export class LiveTvSettingsComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly tuners = signal<Tuner[]>([]);
  readonly channels = signal<Channel[]>([]);
  readonly activeStreams = signal(0);
  readonly maxStreams = signal(2);
  readonly idleTimeout = signal(60);
  readonly minRating = signal(4);
  readonly saving = signal(false);
  readonly saved = signal(false);

  readonly tunerColumns = ['name', 'model', 'ip', 'tuners', 'firmware', 'actions'];
  readonly channelColumns = ['enabled', 'number', 'name', 'affiliation', 'quality'];

  // Add tuner dialog
  readonly addDialogOpen = signal(false);
  readonly addIp = signal('');
  readonly addError = signal('');
  readonly discovering = signal(false);

  // Edit tuner dialog
  readonly editTunerOpen = signal(false);
  readonly editTunerId = signal(0);
  readonly editTunerName = signal('');
  readonly editTunerIp = signal('');

  readonly qualityStars = [1, 2, 3, 4, 5];
  readonly ratingOptions = [
    { value: 0, label: 'TV-Y' },
    { value: 1, label: 'TV-Y7' },
    { value: 2, label: 'G / TV-G' },
    { value: 3, label: 'PG / TV-PG' },
    { value: 4, label: 'PG-13 / TV-14' },
    { value: 5, label: 'R / TV-MA' },
    { value: 6, label: 'NC-17' },
  ];

  async ngOnInit(): Promise<void> { await this.refresh(); }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const data = await firstValueFrom(this.http.get<{
        tuners: Tuner[]; channels: Channel[]; active_streams: number;
        settings: { live_tv_max_streams: number; live_tv_idle_timeout_seconds: number; live_tv_min_rating: number };
      }>('/api/v2/admin/live-tv'));
      this.tuners.set(data.tuners);
      this.channels.set(data.channels);
      this.activeStreams.set(data.active_streams);
      this.maxStreams.set(data.settings.live_tv_max_streams);
      this.idleTimeout.set(data.settings.live_tv_idle_timeout_seconds);
      this.minRating.set(data.settings.live_tv_min_rating);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  async saveSettings(): Promise<void> {
    this.saving.set(true); this.saved.set(false);
    await firstValueFrom(this.http.post('/api/v2/admin/live-tv/settings', {
      live_tv_max_streams: this.maxStreams(), live_tv_idle_timeout_seconds: this.idleTimeout(), live_tv_min_rating: this.minRating()
    }));
    this.saving.set(false); this.saved.set(true);
  }

  updateSetting(key: string, event: Event): void {
    const v = parseInt((event.target as HTMLInputElement).value, 10);
    if (isNaN(v)) return;
    if (key === 'maxStreams') this.maxStreams.set(v);
    else if (key === 'idleTimeout') this.idleTimeout.set(v);
    this.saved.set(false);
  }

  setMinRating(v: number): void { this.minRating.set(v); this.saved.set(false); }

  // Tuner actions
  async toggleTuner(tuner: Tuner): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/live-tv/tuners/${tuner.id}/toggle`, {}));
    await this.refresh();
  }

  async syncChannels(tuner: Tuner): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/live-tv/tuners/${tuner.id}/sync`, {}));
    await this.refresh();
  }

  async deleteTuner(tuner: Tuner): Promise<void> {
    if (!confirm(`Delete "${tuner.name}" and all its channels?`)) return;
    await firstValueFrom(this.http.delete(`/api/v2/admin/live-tv/tuners/${tuner.id}`));
    await this.refresh();
  }

  openEditTuner(tuner: Tuner): void {
    this.editTunerId.set(tuner.id);
    this.editTunerName.set(tuner.name);
    this.editTunerIp.set(tuner.ip_address);
    this.editTunerOpen.set(true);
  }

  async saveEditTuner(): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/live-tv/tuners/${this.editTunerId()}/update`, {
      name: this.editTunerName(), ip_address: this.editTunerIp()
    }));
    this.editTunerOpen.set(false);
    await this.refresh();
  }

  openAddDialog(): void { this.addIp.set(''); this.addError.set(''); this.addDialogOpen.set(true); }
  closeAddDialog(): void { this.addDialogOpen.set(false); }

  async discoverTuner(): Promise<void> {
    this.discovering.set(true); this.addError.set('');
    try {
      const result = await firstValueFrom(this.http.post<{ ok: boolean; error?: string; name?: string; channels_added?: number }>(
        '/api/v2/admin/live-tv/tuners/discover', { ip: this.addIp() }));
      if (result.ok) { this.closeAddDialog(); await this.refresh(); }
      else this.addError.set(result.error ?? 'Discovery failed');
    } catch { this.addError.set('Request failed'); }
    this.discovering.set(false);
  }

  // Channel actions
  async toggleChannel(ch: Channel): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/live-tv/channels/${ch.id}/toggle`, {}));
    ch.enabled = !ch.enabled;
    this.channels.update(c => [...c]);
  }

  async setQuality(ch: Channel, quality: number): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/live-tv/channels/${ch.id}/update`, { reception_quality: quality }));
    ch.reception_quality = quality;
    this.channels.update(c => [...c]);
  }

  async updateAffiliation(ch: Channel, event: Event): Promise<void> {
    const val = (event.target as HTMLInputElement).value;
    await firstValueFrom(this.http.post(`/api/v2/admin/live-tv/channels/${ch.id}/update`, { network_affiliation: val }));
    ch.network_affiliation = val || null;
  }
}
