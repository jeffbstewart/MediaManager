import { Component, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatCardModule, MatIconModule],
  template: `
    <div class="placeholder-container">
      <mat-card class="placeholder-card">
        <mat-card-content>
          <mat-icon class="placeholder-icon">construction</mat-icon>
          <h2>{{ title }}</h2>
          <p>This page is under construction.</p>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: `
    .placeholder-container {
      display: flex;
      justify-content: center;
      padding: 4rem 1rem;
    }
    .placeholder-card {
      max-width: 400px;
      text-align: center;
    }
    .placeholder-icon {
      font-size: 48px;
      width: 48px;
      height: 48px;
      opacity: 0.5;
      margin-bottom: 1rem;
    }
    h2 {
      margin: 0 0 0.5rem;
    }
    p {
      opacity: 0.7;
      margin: 0;
    }
  `,
})
export class PlaceholderComponent {
  readonly title: string;

  constructor(route: ActivatedRoute) {
    this.title = route.snapshot.data['title'] ?? 'Coming Soon';
  }
}
