import { Component, ChangeDetectionStrategy } from '@angular/core';
import { TitleGridComponent } from '../../shared/title-grid/title-grid';

@Component({
  selector: 'app-tv-shows',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TitleGridComponent],
  template: `
    <div class="content-page">
      <app-title-grid mediaType="TV" label="TV shows" />
    </div>
  `,
  styles: `.content-page { padding: 1.5rem; max-width: 1200px; margin: 0 auto; }`,
})
export class TvShowsComponent {}
