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

  constructor() {
    // Drop any pre-migration auth cookies the browser may still hold
    // before the first request goes out. The server also sends Set-
    // Cookie clears on auth-touching endpoints, but doing it on boot
    // means even the initial /api/v2/auth/discover request is clean.
    this.clearLegacyAuthCookies();
  }

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
    // No JS-set auth cookie any more — REST login sets an HttpOnly
    // session cookie server-side via Set-Cookie. The access token is
    // held in memory only for the (small) population of code that
    // attaches it as a Bearer header explicitly; everything that goes
    // through fetch() / HttpClient picks up the cookie automatically.
    this.scheduleRefresh(expiresInSeconds);
  }

  private clearAccessToken(): void {
    this.accessToken.set(null);
    if (this.refreshTimerId) {
      clearTimeout(this.refreshTimerId);
      this.refreshTimerId = null;
    }
  }

  /**
   * Expire any legacy auth cookies that older builds set from JS.
   * Called once at app boot. Two reasons:
   *   1. Old `mm_jwt` cookies sit in browser jars from pre-migration
   *      sessions and would otherwise keep being sent on every request,
   *      wasting bytes (the server no longer accepts them on gRPC).
   *   2. Forces stale sessions onto the login page, where the new
   *      HttpOnly session cookie is issued.
   * The server also sends Set-Cookie clears on any auth-touching
   * endpoint, but the SPA boot clear handles browsers that haven't
   * yet hit one of those endpoints.
   */
  clearLegacyAuthCookies(): void {
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
