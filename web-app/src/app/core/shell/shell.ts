import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { AuthService } from '../auth.service';
import { CatalogService } from '../catalog.service';
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
export class ShellComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly catalog = inject(CatalogService);
  readonly features = inject(FeatureService);

  readonly routes = AppRoutes;

  async ngOnInit(): Promise<void> {
    try {
      const flags = await this.catalog.getFeatures();
      this.features.update(flags);
    } catch {
      // Non-fatal — nav will show minimal set until home page loads
    }
  }
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
