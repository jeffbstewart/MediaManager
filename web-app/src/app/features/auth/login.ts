import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService, DiscoverResponse } from '../../core/auth.service';

@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatCheckboxModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class LoginComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);

  readonly legalInfo = signal<DiscoverResponse['legal'] | null>(null);
  readonly legalChanged = signal(false);
  readonly error = signal('');
  readonly submitting = signal(false);

  readonly form = this.fb.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
    acceptPrivacy: [false],
    acceptTerms: [false],
  });

  async ngOnInit(): Promise<void> {
    try {
      const discover = await this.auth.discover();
      if (discover.setup_required) {
        this.router.navigate(['/setup']);
        return;
      }
      if (discover.legal) {
        this.legalInfo.set(discover.legal);
        if (discover.legal.privacy_policy_url) {
          this.form.controls.acceptPrivacy.setValidators(Validators.requiredTrue);
          this.form.controls.acceptPrivacy.updateValueAndValidity();
        }
        if (discover.legal.terms_of_use_url) {
          this.form.controls.acceptTerms.setValidators(Validators.requiredTrue);
          this.form.controls.acceptTerms.updateValueAndValidity();
        }
      }
    } catch {
      this.error.set('Cannot reach server');
    }
  }

  async onSubmit(): Promise<void> {
    if (!this.form.valid) return;

    this.submitting.set(true);
    this.error.set('');

    const { username, password } = this.form.value;
    const legal = this.legalInfo();
    const legalVersions = legal ? {
      privacy_policy_version: legal.privacy_policy_version,
      terms_of_use_version: legal.terms_of_use_version,
    } : undefined;

    try {
      const response = await this.auth.login(username!, password!, legalVersions);

      if (response.legal_compliant === false) {
        this.legalChanged.set(true);
        this.submitting.set(false);
        return;
      }

      this.router.navigate(['/']);
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
}
