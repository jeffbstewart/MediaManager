import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { SlicePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { firstValueFrom } from 'rxjs';
import { TimezoneService } from '../../core/timezone.service';

interface Profile {
  id: number;
  username: string;
  display_name: string;
  is_admin: boolean;
  rating_ceiling: string | null;
  live_tv_min_quality: number;
  has_live_tv: boolean;
}

interface Session {
  id: number;
  type: 'browser' | 'device';
  user_agent?: string;
  device_name?: string;
  created_at: string | null;
  last_used_at: string | null;
  expires_at?: string | null;
  is_current: boolean;
}

@Component({
  selector: 'app-profile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SlicePipe, RouterLink, MatIconModule, MatButtonModule, MatProgressSpinnerModule],
  templateUrl: './profile.html',
  styleUrl: './profile.scss',
})
export class ProfileComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly tz = inject(TimezoneService);

  readonly loading = signal(true);
  readonly profile = signal<Profile | null>(null);
  readonly sessions = signal<Session[]>([]);
  readonly hiddenTitles = signal<{ title_id: number; title_name: string; poster_url: string | null; release_year: number | null }[]>([]);

  // Change password modal
  readonly showPasswordDialog = signal(false);
  readonly pwCurrent = signal('');
  readonly pwNew = signal('');
  readonly pwConfirm = signal('');
  readonly pwError = signal('');
  readonly pwSuccess = signal(false);
  readonly pwSubmitting = signal(false);

  async ngOnInit(): Promise<void> {
    try {
      const [profile, sessionData, hiddenData] = await Promise.all([
        firstValueFrom(this.http.get<Profile>('/api/v2/profile')),
        firstValueFrom(this.http.get<{ sessions: Session[] }>('/api/v2/profile/sessions')),
        firstValueFrom(this.http.get<{ titles: { title_id: number; title_name: string; poster_url: string | null; release_year: number | null }[] }>('/api/v2/profile/hidden-titles')),
      ]);
      this.profile.set(profile);
      this.sessions.set(sessionData.sessions);
      this.hiddenTitles.set(hiddenData.titles);
    } catch {
      // handled by empty state
    } finally {
      this.loading.set(false);
    }
  }

  async updateTvQuality(quality: number): Promise<void> {
    await firstValueFrom(this.http.post('/api/v2/profile/tv-quality', { quality }));
    this.profile.update(p => p ? { ...p, live_tv_min_quality: quality } : p);
  }

  async revokeSession(session: Session): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v2/profile/sessions/${session.id}`));
    this.sessions.update(s => s.filter(x => x.id !== session.id || x.type !== session.type));
  }

  async revokeOtherSessions(): Promise<void> {
    await firstValueFrom(this.http.post('/api/v2/profile/sessions/revoke-others', {}));
    this.sessions.update(s => s.filter(x => x.is_current));
  }

  openPasswordDialog(): void {
    this.pwCurrent.set('');
    this.pwNew.set('');
    this.pwConfirm.set('');
    this.pwError.set('');
    this.pwSuccess.set(false);
    this.showPasswordDialog.set(true);
  }

  closePasswordDialog(): void {
    this.showPasswordDialog.set(false);
  }

  updatePwField(field: 'current' | 'new' | 'confirm', event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    if (field === 'current') this.pwCurrent.set(value);
    else if (field === 'new') this.pwNew.set(value);
    else this.pwConfirm.set(value);
  }

  pwValid(): boolean {
    return this.pwCurrent().length > 0 && this.pwNew().length >= 8 &&
      this.pwNew() === this.pwConfirm();
  }

  async submitPasswordChange(): Promise<void> {
    this.pwError.set('');
    this.pwSubmitting.set(true);
    try {
      const result = await firstValueFrom(this.http.post<{ ok: boolean; error?: string }>(
        '/api/v2/profile/change-password',
        { current_password: this.pwCurrent(), new_password: this.pwNew() }
      ));
      if (result.ok) {
        this.pwSuccess.set(true);
      } else {
        this.pwError.set(result.error ?? 'Password change failed');
      }
    } catch {
      this.pwError.set('Request failed');
    } finally {
      this.pwSubmitting.set(false);
    }
  }

  formatDate(dateStr: string | null): string {
    return this.tz.formatDateTime(dateStr);
  }

  qualityStars = [1, 2, 3, 4, 5];

  async unhideTitle(titleId: number): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v2/profile/hidden-titles/${titleId}`));
    this.hiddenTitles.update(list => list.filter(t => t.title_id !== titleId));
  }
}
