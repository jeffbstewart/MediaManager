import { Component, inject, signal, computed, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { firstValueFrom } from 'rxjs';
import { TimezoneService } from '../../core/timezone.service';
import { CanDeactivateComponent } from '../../core/can-deactivate.guard';

interface BuddyKey { id: number; name: string; created_at: string | null; }

@Component({
  selector: 'app-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, MatButtonModule, MatCardModule, MatTableModule],
  templateUrl: './settings.html',
  styleUrl: './settings.scss',
  // beforeunload fires on tab close / refresh / external URL changes —
  // Angular router navigations are covered by canDeactivate below.
  host: { '(window:beforeunload)': 'onBeforeUnload($event)' },
})
export class SettingsComponent implements OnInit, CanDeactivateComponent {
  private readonly http = inject(HttpClient);
  readonly tz = inject(TimezoneService);

  readonly loading = signal(true);
  readonly settings = signal<Record<string, string>>({});
  // Pristine snapshot set after load and after each successful save.
  // Dirty state is derived by comparing current settings to this.
  private readonly pristine = signal<Record<string, string>>({});
  readonly isDirty = computed(() => {
    const a = this.settings(); const b = this.pristine();
    const aKeys = Object.keys(a); const bKeys = Object.keys(b);
    if (aKeys.length !== bKeys.length) return true;
    return aKeys.some(k => a[k] !== b[k]);
  });
  readonly buddyKeys = signal<BuddyKey[]>([]);
  readonly isDocker = signal(false);
  readonly saving = signal(false);
  readonly saved = signal(false);

  // New key dialog
  readonly keyDialogOpen = signal(false);
  readonly keyName = signal('');
  readonly generatedKey = signal('');
  readonly keyGenerated = signal(false);

  readonly buddyColumns = ['name', 'created', 'actions'];

  async ngOnInit(): Promise<void> {
    try {
      const data = await firstValueFrom(this.http.get<{
        settings: Record<string, string>; buddy_keys: BuddyKey[]; is_docker: boolean;
      }>('/api/v2/admin/settings'));
      this.settings.set(data.settings);
      this.pristine.set({ ...data.settings });
      this.buddyKeys.set(data.buddy_keys);
      this.isDocker.set(data.is_docker);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  /**
   * Router CanDeactivate hook. When the form has unsaved edits, prompt
   * the user before allowing navigation. Clean returns true outright.
   */
  canDeactivate(): boolean {
    if (!this.isDirty()) return true;
    return confirm('You have unsaved settings changes. Leave without saving?');
  }

  /**
   * Tab-close / refresh / external-URL handler — Angular's router guard
   * only covers in-app navigation. Setting `returnValue` triggers the
   * browser's native "Leave site?" dialog; modern browsers ignore the
   * specific string.
   */
  onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.isDirty()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }

  updateSetting(key: string, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.settings.update(s => ({ ...s, [key]: value }));
    this.saved.set(false);
  }

  updateCheckbox(key: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.settings.update(s => ({ ...s, [key]: checked ? 'true' : 'false' }));
    this.saved.set(false);
  }

  isChecked(key: string): boolean {
    return this.settings()[key] === 'true';
  }

  async save(): Promise<void> {
    this.saving.set(true);
    this.saved.set(false);
    try {
      await firstValueFrom(this.http.post('/api/v2/admin/settings', this.settings()));
      // Update pristine so the form is no longer dirty.
      this.pristine.set({ ...this.settings() });
      this.saved.set(true);
    } catch { /* ignore */ }
    this.saving.set(false);
  }

  openKeyDialog(): void {
    this.keyName.set('');
    this.generatedKey.set('');
    this.keyGenerated.set(false);
    this.keyDialogOpen.set(true);
  }

  closeKeyDialog(): void {
    this.keyDialogOpen.set(false);
  }

  updateKeyName(event: Event): void {
    this.keyName.set((event.target as HTMLInputElement).value);
  }

  async generateKey(): Promise<void> {
    const name = this.keyName().trim();
    if (!name) return;
    try {
      const result = await firstValueFrom(this.http.post<{ ok: boolean; key: string; name: string }>(
        '/api/v2/admin/settings/buddy-keys', { name }
      ));
      this.generatedKey.set(result.key);
      this.keyGenerated.set(true);
      // Refresh key list
      const data = await firstValueFrom(this.http.get<{ buddy_keys: BuddyKey[] }>('/api/v2/admin/settings'));
      this.buddyKeys.set((data as any).buddy_keys);
    } catch { /* ignore */ }
  }

  async copyKey(): Promise<void> {
    await navigator.clipboard.writeText(this.generatedKey());
  }

  async deleteKey(key: BuddyKey): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v2/admin/settings/buddy-keys/${key.id}`));
    this.buddyKeys.update(keys => keys.filter(k => k.id !== key.id));
  }
}
