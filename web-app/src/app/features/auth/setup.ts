import { Component, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators, AbstractControl, ValidationErrors } from '@angular/forms';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../../core/auth.service';

function passwordMatchValidator(control: AbstractControl): ValidationErrors | null {
  const password = control.get('password')?.value;
  const confirmControl = control.get('confirmPassword');
  const confirm = confirmControl?.value;
  if (password && confirm && password !== confirm) {
    confirmControl!.setErrors({ ...confirmControl!.errors, passwordMismatch: true });
    return { passwordMismatch: true };
  }
  // Clear only our error, preserve other validators' errors
  if (confirmControl?.hasError('passwordMismatch')) {
    const { passwordMismatch, ...rest } = confirmControl.errors!;
    confirmControl.setErrors(Object.keys(rest).length ? rest : null);
  }
  return null;
}

@Component({
  selector: 'app-setup',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './setup.html',
  styleUrl: './setup.scss',
})
export class SetupComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);

  readonly error = signal('');
  readonly submitting = signal(false);

  readonly form = this.fb.group({
    username: ['', Validators.required],
    password: ['', [Validators.required, Validators.minLength(8)]],
    confirmPassword: ['', Validators.required],
    privacyPolicyUrl: ['about:blank', [Validators.required, Validators.pattern(/^(https:\/\/.+|about:blank)$/)]],
    termsOfUseUrl: ['about:blank', [Validators.required, Validators.pattern(/^(https:\/\/.+|about:blank)$/)]],
  }, { validators: passwordMatchValidator });

  async onSubmit(): Promise<void> {
    if (!this.form.valid) return;

    const { password } = this.form.value;

    this.submitting.set(true);
    this.error.set('');

    try {
      await this.auth.setup({
        username: this.form.value.username!,
        password: password!,
        privacy_policy_url: this.form.value.privacyPolicyUrl!,
        terms_of_use_url: this.form.value.termsOfUseUrl!,
      });

      this.router.navigate(['/']);
    } catch (e: unknown) {
      const httpError = e as { error?: { error?: string } };
      this.error.set(httpError.error?.error ?? 'Setup failed');
      this.submitting.set(false);
    }
  }
}
