import { Component, ChangeDetectionStrategy } from '@angular/core';
import { TitleGridComponent } from '../../shared/title-grid/title-grid';

@Component({
  selector: 'app-movies',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TitleGridComponent],
  template: `
    <div class="content-page">
      <app-title-grid mediaType="MOVIE" label="movies" />
    </div>
  `,
  styles: `.content-page { padding: 1.5rem; max-width: 1200px; margin: 0 auto; }`,
})
export class MoviesComponent {}
