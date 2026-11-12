import {
  Component, inject, input, output, signal, ChangeDetectionStrategy,
} from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';

@Component({
  selector: 'app-report-problem-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatButtonModule],
  templateUrl: './report-problem-dialog.html',
  styleUrl: './report-problem-dialog.scss',
})
export class ReportProblemDialogComponent {
  private readonly http = inject(HttpClient);

  readonly open = input(false);
  readonly titleId = input<number | null>(null);
  readonly titleName = input<string | null>(null);
  readonly seasonNumber = input<number | null>(null);
  readonly episodeNumber = input<number | null>(null);

  readonly closed = output<void>();
  readonly submitted = output<void>();

  readonly description = signal('');
  readonly submitting = signal(false);
  readonly done = signal(false);

  close(): void {
    this.description.set('');
    this.done.set(false);
    this.closed.emit();
  }

  async submit(): Promise<void> {
    const desc = this.description().trim();
    if (!desc) return;

    this.submitting.set(true);
    try {
      await firstValueFrom(this.http.post('/api/v2/reports', {
        title_id: this.titleId(),
        title_name: this.titleName(),
        season_number: this.seasonNumber(),
        episode_number: this.episodeNumber(),
        description: desc,
      }));
      this.done.set(true);
      this.submitted.emit();
    } catch {
      // Best-effort — dialog stays open so user can retry
    }
    this.submitting.set(false);
  }
}
