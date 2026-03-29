import { Component, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { AuthService } from '../auth.service';
import { FeatureService } from '../feature.service';
import { AppRoutes } from '../routes';

@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatIconModule,
    MatButtonModule,
    MatListModule,
    MatMenuModule,
    MatDividerModule,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class ShellComponent {
  private readonly auth = inject(AuthService);
  readonly features = inject(FeatureService);

  readonly routes = AppRoutes;
  readonly purchasesOpen = signal(false);
  readonly transcodesOpen = signal(false);

  togglePurchases(): void {
    this.purchasesOpen.update(v => !v);
  }

  toggleTranscodes(): void {
    this.transcodesOpen.update(v => !v);
  }

  onLogout(): void {
    this.auth.logout();
  }
}
