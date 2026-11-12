import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import {
  startAuthentication,
  startRegistration,
  type PublicKeyCredentialCreationOptionsJSON,
  type PublicKeyCredentialRequestOptionsJSON,
  type AuthenticationResponseJSON,
  type RegistrationResponseJSON,
} from '@simplewebauthn/browser';
import { firstValueFrom } from 'rxjs';

export interface Passkey {
  id: number;
  display_name: string;
  created_at: string | null;
  last_used_at: string | null;
}

interface ChallengeResponse<T> {
  challenge: string;
  options: T;
}

interface RegisterResult {
  ok: boolean;
  credential: { id: number; display_name: string; created_at: string };
}

@Injectable({ providedIn: 'root' })
export class WebAuthnService {
  private readonly http = inject(HttpClient);

  /** Whether the browser supports WebAuthn. */
  isSupported(): boolean {
    return typeof window !== 'undefined' && !!window.PublicKeyCredential;
  }

  // --- Authentication (login page) ---

  async getAuthenticationOptions(): Promise<ChallengeResponse<PublicKeyCredentialRequestOptionsJSON>> {
    return firstValueFrom(
      this.http.post<ChallengeResponse<PublicKeyCredentialRequestOptionsJSON>>(
        '/api/v2/auth/passkey/authentication-options', {}
      )
    );
  }

  async authenticate(challenge: string, credential: AuthenticationResponseJSON): Promise<{
    access_token: string;
    expires_in: number;
    password_change_required?: boolean;
  }> {
    return firstValueFrom(
      this.http.post<{
        access_token: string;
        expires_in: number;
        password_change_required?: boolean;
      }>('/api/v2/auth/passkey/authenticate', { challenge, credential })
    );
  }

  // --- Registration (profile page) ---

  async getRegistrationOptions(): Promise<ChallengeResponse<PublicKeyCredentialCreationOptionsJSON>> {
    return firstValueFrom(
      this.http.post<ChallengeResponse<PublicKeyCredentialCreationOptionsJSON>>(
        '/api/v2/profile/passkeys/registration-options', {}
      )
    );
  }

  async register(
    challenge: string,
    credential: RegistrationResponseJSON,
    displayName?: string
  ): Promise<RegisterResult> {
    return firstValueFrom(
      this.http.post<RegisterResult>('/api/v2/profile/passkeys/register', {
        challenge,
        credential,
        display_name: displayName || 'Passkey',
      })
    );
  }

  async listPasskeys(): Promise<Passkey[]> {
    const res = await firstValueFrom(
      this.http.get<{ passkeys: Passkey[] }>('/api/v2/profile/passkeys')
    );
    return res.passkeys;
  }

  async deletePasskey(id: number): Promise<void> {
    await firstValueFrom(
      this.http.delete(`/api/v2/profile/passkeys/${id}`)
    );
  }

  // --- High-level helpers ---

  /** Full passkey authentication ceremony. Returns token response. */
  async performAuthentication(): Promise<{
    access_token: string;
    expires_in: number;
    password_change_required?: boolean;
  }> {
    const { challenge, options } = await this.getAuthenticationOptions();
    const credential = await startAuthentication({ optionsJSON: options });
    return this.authenticate(challenge, credential);
  }

  /** Full passkey registration ceremony. Returns the new credential. */
  async performRegistration(displayName?: string): Promise<RegisterResult> {
    const { challenge, options } = await this.getRegistrationOptions();
    const credential = await startRegistration({ optionsJSON: options });
    return this.register(challenge, credential, displayName);
  }
}
