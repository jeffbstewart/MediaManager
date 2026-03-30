import { Component, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { firstValueFrom } from 'rxjs';

@Component({
  selector: 'app-change-password',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule],
  templateUrl: './change-password.html',
  styleUrl: './change-password.scss',
})
export class ChangePasswordComponent {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  readonly currentPassword = signal('');
  readonly newPassword = signal('');
  readonly confirmPassword = signal('');
  readonly error = signal('');
  readonly success = signal(false);
  readonly submitting = signal(false);
  readonly attempts = signal(0);
  readonly maxAttempts = 5;

  get isValid(): boolean {
    return this.currentPassword().length > 0
      && this.newPassword().length >= 8
      && this.newPassword() === this.confirmPassword()
      && this.attempts() < this.maxAttempts;
  }

  get passwordMismatch(): boolean {
    return this.confirmPassword().length > 0 && this.newPassword() !== this.confirmPassword();
  }

  get passwordTooShort(): boolean {
    return this.newPassword().length > 0 && this.newPassword().length < 8;
  }

  updateCurrent(event: Event): void { this.currentPassword.set((event.target as HTMLInputElement).value); }
  updateNew(event: Event): void { this.newPassword.set((event.target as HTMLInputElement).value); }
  updateConfirm(event: Event): void { this.confirmPassword.set((event.target as HTMLInputElement).value); }

  async onSubmit(): Promise<void> {
    if (!this.isValid) return;
    this.submitting.set(true);
    this.error.set('');

    try {
      const result = await firstValueFrom(this.http.post<{ ok: boolean; error?: string }>(
        '/api/v2/profile/change-password', {
          current_password: this.currentPassword(),
          new_password: this.newPassword(),
        }));

      if (result.ok) {
        this.success.set(true);
        setTimeout(() => this.router.navigate(['/']), 2000);
      } else {
        this.attempts.update(a => a + 1);
        const remaining = this.maxAttempts - this.attempts();
        this.error.set(result.error ?? 'Password change failed');
        if (remaining > 0) {
          this.error.update(e => `${e} (${remaining} attempt${remaining === 1 ? '' : 's'} remaining)`);
        } else {
          this.error.set('Too many failed attempts. Please sign out and try again.');
        }
      }
    } catch {
      this.error.set('Request failed');
    }
    this.submitting.set(false);
  }
}
