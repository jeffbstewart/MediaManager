import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ThemeService } from './theme.service';

/** The fields of `GET /api/v2/profile` the shell and the home page show. */
export interface AccountSummary {
  username: string;
  is_admin: boolean;
}

interface ProfileResponse extends AccountSummary {
  web_theme?: string | null;
  default_theme?: string;
}

/**
 * The signed-in account, loaded once per page load from the profile
 * endpoint and shared by the header's account pill and the home page's
 * welcome block, so the second consumer costs no extra request. Loading
 * also hands the profile's theme fields to [ThemeService], which is why
 * the pill applies the server's theme on every page.
 */
@Injectable({ providedIn: 'root' })
export class AccountService {
  private readonly http = inject(HttpClient);
  private readonly theme = inject(ThemeService);
  private readonly accountSignal = signal<AccountSummary | null>(null);
  private loading: Promise<void> | null = null;

  /** Null until the profile loads, and if it fails. */
  readonly account = this.accountSignal.asReadonly();

  /** Starts the load once; later calls return the same promise. */
  load(): Promise<void> {
    this.loading ??= this.fetch();
    return this.loading;
  }

  private async fetch(): Promise<void> {
    try {
      const p = await firstValueFrom(this.http.get<ProfileResponse>('/api/v2/profile'));
      this.accountSignal.set({ username: p.username, is_admin: p.is_admin });
      this.theme.applyFromServer(p.web_theme, p.default_theme);
    } catch {
      // Consumers fall back: the pill shows the icon alone, the welcome has no name.
    }
  }
}
