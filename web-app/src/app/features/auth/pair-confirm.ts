import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { firstValueFrom } from 'rxjs';

type PageState = 'loading' | 'confirm' | 'success' | 'error' | 'already_paired';

@Component({
  selector: 'app-pair-confirm',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './pair-confirm.html',
  styleUrl: './pair-confirm.scss',
})
export class PairConfirmComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);

  readonly state = signal<PageState>('loading');
  readonly displayName = signal('');
  readonly deviceName = signal('');
  readonly errorMessage = signal('');
  readonly pairedUser = signal('');
  readonly submitting = signal(false);

  private code = '';

  async ngOnInit(): Promise<void> {
    this.code = this.route.snapshot.queryParamMap.get('code') ?? '';
    if (!this.code) {
      this.errorMessage.set('No pairing code provided.');
      this.state.set('error');
      return;
    }

    try {
      const info = await firstValueFrom(this.http.get<{
        status: string; username?: string; display_name?: string;
      }>('/api/v2/pair/info', { params: { code: this.code } }));

      if (info.status === 'paired') {
        this.pairedUser.set(info.username ?? 'unknown');
        this.state.set('already_paired');
      } else if (info.status === 'expired') {
        this.errorMessage.set('This pairing code has expired or is invalid. Please start a new pairing on your device.');
        this.state.set('error');
      } else {
        this.displayName.set(info.display_name ?? '');
        this.state.set('confirm');
      }
    } catch {
      this.errorMessage.set('Unable to check pairing status.');
      this.state.set('error');
    }
  }

  async confirm(): Promise<void> {
    this.submitting.set(true);
    try {
      const result = await firstValueFrom(this.http.post<{
        ok: boolean; device_name?: string; error?: string;
      }>('/api/v2/pair/confirm', { code: this.code }));

      if (result.ok) {
        this.deviceName.set(result.device_name ?? 'Device');
        this.state.set('success');
      } else {
        this.errorMessage.set(result.error ?? 'Pairing failed');
        this.state.set('error');
      }
    } catch {
      this.errorMessage.set('Request failed');
      this.state.set('error');
    }
    this.submitting.set(false);
  }
}
