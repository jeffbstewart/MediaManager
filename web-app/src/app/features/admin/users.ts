import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { firstValueFrom } from 'rxjs';
import { TimezoneService } from '../../core/timezone.service';

interface UserRow {
  id: number;
  username: string;
  access_level: number;
  locked: boolean;
  must_change_password: boolean;
  rating_ceiling: number | null;
  created_at: string | null;
}

const RATING_CHOICES = [
  { value: null, label: 'Unrestricted' },
  { value: 0, label: 'TV-Y' },
  { value: 1, label: 'TV-Y7' },
  { value: 2, label: 'G / TV-G' },
  { value: 3, label: 'PG / TV-PG' },
  { value: 4, label: 'PG-13 / TV-14' },
  { value: 5, label: 'R / TV-MA' },
  { value: 6, label: 'NC-17' },
];

@Component({
  selector: 'app-users',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, MatTableModule, MatButtonModule, MatMenuModule, MatDividerModule],
  templateUrl: './users.html',
  styleUrl: './users.scss',
})
export class UsersComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly tz = inject(TimezoneService);

  readonly loading = signal(true);
  readonly users = signal<UserRow[]>([]);
  readonly columns = ['username', 'role', 'rating', 'created', 'actions'];
  readonly ratingChoices = RATING_CHOICES;

  // Add user dialog
  readonly addDialogOpen = signal(false);
  readonly addUsername = signal('');
  readonly addPassword = signal('');
  readonly addConfirm = signal('');
  readonly addForceChange = signal(true);
  readonly addError = signal('');

  // Reset password dialog
  readonly resetDialogOpen = signal(false);
  readonly resetUser = signal<UserRow | null>(null);
  readonly resetPassword = signal('');
  readonly resetConfirm = signal('');
  readonly resetError = signal('');

  // Sessions dialog
  readonly sessionsDialogOpen = signal(false);
  readonly sessionsUser = signal<UserRow | null>(null);
  readonly sessions = signal<{ id: number; type: string; user_agent?: string; device_name?: string; last_used_at: string | null }[]>([]);

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const data = await firstValueFrom(this.http.get<{ users: UserRow[] }>('/api/v2/admin/users'));
      this.users.set(data.users);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  ratingLabel(ceiling: number | null): string {
    if (ceiling == null) return 'Unrestricted';
    return RATING_CHOICES.find(c => c.value === ceiling)?.label ?? 'Unknown';
  }

  // Actions
  async promote(user: UserRow): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/users/${user.id}/promote`, {}));
    await this.refresh();
  }

  async demote(user: UserRow): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/users/${user.id}/demote`, {}));
    await this.refresh();
  }

  async unlock(user: UserRow): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/users/${user.id}/unlock`, {}));
    await this.refresh();
  }

  async forcePasswordChange(user: UserRow): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/users/${user.id}/force-password-change`, {}));
    await this.refresh();
  }

  async setRatingCeiling(user: UserRow, value: number | null): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/users/${user.id}/rating-ceiling`, { rating_ceiling: value }));
    await this.refresh();
  }

  async deleteUser(user: UserRow): Promise<void> {
    if (!confirm(`Delete user "${user.username}"? This cannot be undone.`)) return;
    await firstValueFrom(this.http.delete(`/api/v2/admin/users/${user.id}`));
    await this.refresh();
  }

  // Add user dialog
  openAddDialog(): void {
    this.addUsername.set('');
    this.addPassword.set('');
    this.addConfirm.set('');
    this.addForceChange.set(true);
    this.addError.set('');
    this.addDialogOpen.set(true);
  }

  closeAddDialog(): void { this.addDialogOpen.set(false); }

  updateAddField(field: string, event: Event): void {
    const v = (event.target as HTMLInputElement).value;
    if (field === 'username') this.addUsername.set(v);
    else if (field === 'password') this.addPassword.set(v);
    else if (field === 'confirm') this.addConfirm.set(v);
  }

  updateAddForceChange(event: Event): void {
    this.addForceChange.set((event.target as HTMLInputElement).checked);
  }

  addValid(): boolean {
    return this.addUsername().trim().length > 0 && this.addPassword().length >= 8 &&
      this.addPassword() === this.addConfirm();
  }

  async submitAddUser(): Promise<void> {
    this.addError.set('');
    try {
      const result = await firstValueFrom(this.http.post<{ ok: boolean; error?: string }>('/api/v2/admin/users', {
        username: this.addUsername().trim(),
        password: this.addPassword(),
        force_change: this.addForceChange(),
      }));
      if (!result.ok) { this.addError.set(result.error ?? 'Failed'); return; }
      this.closeAddDialog();
      await this.refresh();
    } catch {
      this.addError.set('Request failed');
    }
  }

  // Reset password dialog
  openResetDialog(user: UserRow): void {
    this.resetUser.set(user);
    this.resetPassword.set('');
    this.resetConfirm.set('');
    this.resetError.set('');
    this.resetDialogOpen.set(true);
  }

  closeResetDialog(): void { this.resetDialogOpen.set(false); this.resetUser.set(null); }

  updateResetField(field: string, event: Event): void {
    const v = (event.target as HTMLInputElement).value;
    if (field === 'password') this.resetPassword.set(v);
    else this.resetConfirm.set(v);
  }

  resetValid(): boolean {
    return this.resetPassword().length >= 8 && this.resetPassword() === this.resetConfirm();
  }

  async submitResetPassword(): Promise<void> {
    const user = this.resetUser();
    if (!user) return;
    this.resetError.set('');
    try {
      const result = await firstValueFrom(this.http.post<{ ok: boolean; error?: string }>(
        `/api/v2/admin/users/${user.id}/reset-password`, { password: this.resetPassword() }
      ));
      if (!result.ok) { this.resetError.set(result.error ?? 'Failed'); return; }
      this.closeResetDialog();
      await this.refresh();
    } catch {
      this.resetError.set('Request failed');
    }
  }

  // Sessions dialog
  async openSessions(user: UserRow): Promise<void> {
    this.sessionsUser.set(user);
    this.sessions.set([]);
    this.sessionsDialogOpen.set(true);
    try {
      const data = await firstValueFrom(this.http.get<{ sessions: any[] }>(`/api/v2/admin/users/${user.id}/sessions`));
      this.sessions.set(data.sessions);
    } catch { /* ignore */ }
  }

  closeSessionsDialog(): void { this.sessionsDialogOpen.set(false); this.sessionsUser.set(null); }

  async revokeSession(session: { id: number }): Promise<void> {
    const user = this.sessionsUser();
    if (!user) return;
    await firstValueFrom(this.http.delete(`/api/v2/admin/users/${user.id}/sessions/${session.id}`));
    this.sessions.update(s => s.filter(x => x.id !== session.id));
  }

  async revokeAllSessions(): Promise<void> {
    const user = this.sessionsUser();
    if (!user) return;
    await firstValueFrom(this.http.post(`/api/v2/admin/users/${user.id}/revoke-all-sessions`, {}));
    this.sessions.set([]);
  }

  formatSessionDate(dateStr: string | null): string {
    return this.tz.formatDateTime(dateStr);
  }
}
