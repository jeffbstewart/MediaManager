import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule],
  template: `
    <div class="home-container">
      <h1>Media Manager</h1>
      <p>You are logged in.</p>
      <button mat-flat-button (click)="onLogout()">Sign Out</button>
    </div>
  `,
  styles: `
    .home-container {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 2rem;
    }
  `,
})
export class HomeComponent {
  private readonly auth = inject(AuthService);

  onLogout(): void {
    this.auth.logout();
  }
}
