import { inject, Injectable, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

export interface DiscoverResponse {
  setup_required: boolean;
  legal?: {
    privacy_policy_url?: string;
    privacy_policy_version?: number;
    terms_of_use_url?: string;
    terms_of_use_version?: number;
  };
}

export interface LoginResponse {
  access_token: string;
  /** Token lifetime in seconds (RFC 6749). */
  expires_in: number;
  password_change_required?: boolean;
  legal_compliant?: boolean;
  agreed_privacy_policy_version?: number;
  agreed_terms_of_use_version?: number;
  required_privacy_policy_version?: number;
  required_terms_of_use_version?: number;
}

export interface SetupRequest {
  username: string;
  password: string;
  privacy_policy_url: string;
  terms_of_use_url: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly accessToken = signal<string | null>(null);
  private refreshTimerId: ReturnType<typeof setTimeout> | null = null;

  readonly isAuthenticated = computed(() => this.accessToken() !== null);

  async discover(): Promise<DiscoverResponse> {
    return firstValueFrom(this.http.get<DiscoverResponse>('/api/v2/auth/discover'));
  }

  async login(username: string, password: string, legalVersions?: {
    privacy_policy_version?: number;
    terms_of_use_version?: number;
  }): Promise<LoginResponse> {
    const body: Record<string, unknown> = { username, password };
    if (legalVersions?.privacy_policy_version) {
      body['privacy_policy_version'] = legalVersions.privacy_policy_version;
    }
    if (legalVersions?.terms_of_use_version) {
      body['terms_of_use_version'] = legalVersions.terms_of_use_version;
    }

    const response = await firstValueFrom(
      this.http.post<LoginResponse>('/api/v2/auth/login', body)
    );

    this.setAccessToken(response.access_token, response.expires_in);
    return response;
  }

  async setup(request: SetupRequest): Promise<LoginResponse> {
    const response = await firstValueFrom(
      this.http.post<LoginResponse>('/api/v2/auth/setup', request)
    );

    this.setAccessToken(response.access_token, response.expires_in);
    return response;
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.http.post('/api/v2/auth/logout', {}));
    } catch {
      // Best-effort — cookie cleared server-side
    }
    this.clearAccessToken();
    this.router.navigate(['/login']);
  }

  async tryRefresh(): Promise<boolean> {
    try {
      const response = await firstValueFrom(
        this.http.post<LoginResponse>('/api/v2/auth/refresh', {})
      );
      this.setAccessToken(response.access_token, response.expires_in);
      return true;
    } catch {
      this.clearAccessToken();
      return false;
    }
  }

  getAccessToken(): string | null {
    return this.accessToken();
  }

  private setAccessToken(token: string, expiresInSeconds: number): void {
    this.accessToken.set(token);
    this.scheduleRefresh(expiresInSeconds);
  }

  private clearAccessToken(): void {
    this.accessToken.set(null);
    if (this.refreshTimerId) {
      clearTimeout(this.refreshTimerId);
      this.refreshTimerId = null;
    }
  }

  /** Schedule a token refresh at 80% of the token lifetime. */
  private scheduleRefresh(expiresInSeconds: number): void {
    if (this.refreshTimerId) {
      clearTimeout(this.refreshTimerId);
    }
    const refreshMs = expiresInSeconds * 800; // 80% of lifetime in ms
    this.refreshTimerId = setTimeout(() => this.tryRefresh(), refreshMs);
  }
}
