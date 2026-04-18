import { Component, ChangeDetectionStrategy } from '@angular/core';
import { TitleGridComponent } from '../../shared/title-grid/title-grid';

@Component({
  selector: 'app-books',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TitleGridComponent],
  template: `
    <div class="content-page">
      <app-title-grid mediaType="BOOK" label="books" />
    </div>
  `,
  styles: `.content-page { padding: 1.5rem; max-width: 1200px; margin: 0 auto; }`,
})
export class BooksComponent {}
