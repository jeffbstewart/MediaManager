import { inject, Injectable, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { WebAuthnService } from './webauthn.service';

export interface DiscoverResponse {
  setup_required: boolean;
  passkeys_available?: boolean;
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
}

export interface LegalStatus {
  compliant: boolean;
  required_privacy_policy_version: number;
  required_terms_of_use_version: number;
  agreed_privacy_policy_version: number | null;
  agreed_terms_of_use_version: number | null;
  privacy_policy_url: string | null;
  terms_of_use_url: string | null;
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
  private readonly webauthn = inject(WebAuthnService);

  private readonly accessToken = signal<string | null>(null);
  private refreshTimerId: ReturnType<typeof setTimeout> | null = null;

  readonly isAuthenticated = computed(() => this.accessToken() !== null);

  /** Cached legal status — populated after login/refresh, cleared on logout. */
  readonly legalStatus = signal<LegalStatus | null>(null);

  async discover(): Promise<DiscoverResponse> {
    return firstValueFrom(this.http.get<DiscoverResponse>('/api/v2/auth/discover'));
  }

  async login(username: string, password: string): Promise<LoginResponse> {
    const response = await firstValueFrom(
      this.http.post<LoginResponse>('/api/v2/auth/login', { username, password })
    );

    this.setAccessToken(response.access_token, response.expires_in);
    this.legalStatus.set(null); // Force re-check on next guard
    return response;
  }

  /** Authenticate using a passkey. Returns the same shape as password login. */
  async loginWithPasskey(): Promise<LoginResponse> {
    const result = await this.webauthn.performAuthentication();
    this.setAccessToken(result.access_token, result.expires_in);
    this.legalStatus.set(null);
    return result;
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
    this.legalStatus.set(null);
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

  /**
   * Check legal compliance status. Uses cached value if available.
   * Call clearLegalStatus() to force a re-check.
   */
  async checkLegalStatus(): Promise<LegalStatus> {
    const cached = this.legalStatus();
    if (cached) return cached;

    const status = await firstValueFrom(
      this.http.get<LegalStatus>('/api/v2/legal/status')
    );
    this.legalStatus.set(status);
    return status;
  }

  /** Clear the cached legal status (e.g., on 451 response). */
  clearLegalStatus(): void {
    this.legalStatus.set(null);
  }

  /** Submit agreement to current legal terms. */
  async agreeToTerms(ppVersion: number, touVersion: number): Promise<void> {
    await firstValueFrom(
      this.http.post('/api/v2/legal/agree', {
        privacy_policy_version: ppVersion,
        terms_of_use_version: touVersion,
      })
    );
    this.legalStatus.set(null); // Clear cache so next check picks up agreement
  }

  getAccessToken(): string | null {
    return this.accessToken();
  }

  private setAccessToken(token: string, expiresInSeconds: number): void {
    this.accessToken.set(token);
    this.setJwtCookie(token, expiresInSeconds);
    this.scheduleRefresh(expiresInSeconds);
  }

  private clearAccessToken(): void {
    this.accessToken.set(null);
    this.clearJwtCookie();
    if (this.refreshTimerId) {
      clearTimeout(this.refreshTimerId);
      this.refreshTimerId = null;
    }
  }

  /**
   * Sets the access token as an mm_jwt cookie so browser-initiated requests
   * (<img>, <video>) carry auth automatically. The ArmeriaAuthDecorator
   * accepts this cookie as auth method #3.
   *
   * TODO: Pair with HttpOnly mm_jwt_bind cookie for off-origin theft
   * protection (see issue #59).
   */
  private setJwtCookie(token: string, expiresInSeconds: number): void {
    const expires = new Date(Date.now() + expiresInSeconds * 1000).toUTCString();
    document.cookie = `mm_jwt=${token}; path=/; expires=${expires}; SameSite=Lax`;
  }

  private clearJwtCookie(): void {
    document.cookie = 'mm_jwt=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT; SameSite=Lax';
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
