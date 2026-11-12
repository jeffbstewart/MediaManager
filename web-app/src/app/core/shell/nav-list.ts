import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { FeatureService } from '../feature.service';
import { AppRoutes } from '../routes';

/**
 * Primary navigation: the main links, the feature-flag-gated entries,
 * the admin MANAGE group with its badges, and the Purchases and
 * Transcodes collapsible groups, as plain pill links. Rendered
 * in the shell's side nav and in its phone nav sheet.
 */
@Component({
  selector: 'app-nav-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive, MatIconModule],
  templateUrl: './nav-list.html',
  styleUrl: './nav-list.scss',
})
export class NavListComponent {
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
}
