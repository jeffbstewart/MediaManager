import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService, LegalStatus } from '../../core/auth.service';

@Component({
  selector: 'app-terms',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatCheckboxModule,
    MatButtonModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './terms.html',
  styleUrl: './terms.scss',
})
export class TermsComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);

  readonly status = signal<LegalStatus | null>(null);
  readonly error = signal('');
  readonly submitting = signal(false);

  readonly form = this.fb.group({
    acceptPrivacy: [false],
    acceptTerms: [false],
  });

  async ngOnInit(): Promise<void> {
    try {
      // Force a fresh check (clear cache)
      this.auth.clearLegalStatus();
      const legalStatus = await this.auth.checkLegalStatus();

      if (legalStatus.compliant) {
        this.navigateAway();
        return;
      }

      this.status.set(legalStatus);

      if (legalStatus.privacy_policy_url) {
        this.form.controls.acceptPrivacy.setValidators(Validators.requiredTrue);
        this.form.controls.acceptPrivacy.updateValueAndValidity();
      }
      if (legalStatus.terms_of_use_url) {
        this.form.controls.acceptTerms.setValidators(Validators.requiredTrue);
        this.form.controls.acceptTerms.updateValueAndValidity();
      }
    } catch {
      this.error.set('Unable to load legal requirements');
    }
  }

  async onSubmit(): Promise<void> {
    if (!this.form.valid) return;

    const legalStatus = this.status();
    if (!legalStatus) return;

    this.submitting.set(true);
    this.error.set('');

    try {
      await this.auth.agreeToTerms(
        legalStatus.required_privacy_policy_version,
        legalStatus.required_terms_of_use_version
      );
      this.navigateAway();
    } catch {
      this.error.set('Failed to record agreement. Please try again.');
      this.submitting.set(false);
    }
  }

  private navigateAway(): void {
    const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') || '/';
    this.router.navigateByUrl(returnUrl);
  }
}
