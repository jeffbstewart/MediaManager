import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { FeatureService } from '../feature.service';
import { AppRoutes } from '../routes';
import { PageSection } from './page-section';

interface Tab {
  section: PageSection;
  label: string;
  icon: string;
  link: string;
}

/**
 * Bottom tab bar (spec `nav.tabbar`): fixed to the bottom of the
 * viewport below 1024px, hidden at 1024px and up where the side nav
 * shows. Home, Live TV when enabled, Movies & TV, Discover when
 * enabled. The active tab carries aria-current="page".
 */
@Component({
  selector: 'app-tab-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule],
  template: `
    <nav class="tabbar" aria-label="Tabs">
      @for (tab of tabs(); track tab.section) {
        <a [routerLink]="tab.link" [attr.aria-current]="tab.section === active() ? 'page' : null">
          <mat-icon aria-hidden="true">{{ tab.icon }}</mat-icon>
          <span>{{ tab.label }}</span>
        </a>
      }
    </nav>
  `,
  styleUrl: './tab-bar.scss',
})
export class TabBarComponent {
  private readonly features = inject(FeatureService);

  /** The section of the current page, or null when none matches. */
  readonly active = input<PageSection | null>(null);

  readonly tabs = computed<Tab[]>(() => {
    const tabs: Tab[] = [{ section: 'home', label: 'Home', icon: 'home', link: AppRoutes.home() }];
    if (this.features.hasLiveTv()) {
      tabs.push({ section: 'live-tv', label: 'Live TV', icon: 'live_tv', link: AppRoutes.liveTv() });
    }
    tabs.push({ section: 'movies-tv', label: 'Movies & TV', icon: 'movie', link: AppRoutes.movies() });
    if (this.features.hasMusicRadio()) {
      tabs.push({ section: 'discover', label: 'Discover', icon: 'auto_awesome', link: AppRoutes.discover() });
    }
    return tabs;
  });
}
