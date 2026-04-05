import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { AuthService } from '../../core/auth.service';
import { WebAuthnService } from '../../core/webauthn.service';

@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    MatDividerModule,
  ],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class LoginComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly webauthn = inject(WebAuthnService);

  readonly passkeysAvailable = signal(false);
  readonly error = signal('');
  readonly submitting = signal(false);
  readonly passkeyLoading = signal(false);

  readonly form = this.fb.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  async ngOnInit(): Promise<void> {
    // If already authenticated, redirect to destination
    if (this.auth.isAuthenticated()) {
      this.navigateAway();
      return;
    }
    // Try silent refresh — user may have a valid refresh cookie
    if (await this.auth.tryRefresh()) {
      this.navigateAway();
      return;
    }

    try {
      const discover = await this.auth.discover();
      if (discover.setup_required) {
        this.router.navigate(['/setup']);
        return;
      }
      this.passkeysAvailable.set(
        !!discover.passkeys_available && this.webauthn.isSupported()
      );
    } catch {
      this.error.set('Cannot reach server');
    }
  }

  async onSubmit(): Promise<void> {
    if (!this.form.valid) return;

    this.submitting.set(true);
    this.error.set('');

    const { username, password } = this.form.value;

    try {
      const response = await this.auth.login(username!, password!);

      if (response.password_change_required) {
        this.router.navigate(['/change-password']);
        return;
      }

      this.navigateAway();
    } catch (e: unknown) {
      const httpError = e as { error?: { error?: string; retry_after?: number } };
      const retryAfter = httpError.error?.retry_after;
      if (retryAfter && retryAfter > 0) {
        this.error.set(`Too many attempts. Try again in ${retryAfter} seconds.`);
        setTimeout(() => this.submitting.set(false), retryAfter * 1000);
      } else {
        this.error.set(httpError.error?.error ?? 'Login failed');
        this.submitting.set(false);
      }
    }
  }

  async onPasskeyLogin(): Promise<void> {
    this.passkeyLoading.set(true);
    this.error.set('');

    try {
      const response = await this.auth.loginWithPasskey();

      if (response.password_change_required) {
        this.router.navigate(['/change-password']);
        return;
      }

      this.navigateAway();
    } catch (e: unknown) {
      // User cancelled the passkey dialog — not an error
      if (e instanceof DOMException && e.name === 'NotAllowedError') {
        this.passkeyLoading.set(false);
        return;
      }
      const httpError = e as { error?: { error?: string } };
      this.error.set(httpError.error?.error ?? 'Passkey authentication failed');
      this.passkeyLoading.set(false);
    }
  }

  private navigateAway(): void {
    const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') || '/';
    this.router.navigateByUrl(returnUrl);
  }
}
