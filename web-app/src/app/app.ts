import { Component, ChangeDetectionStrategy, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/theme.service';

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet],
  template: '<router-outlet />',
})
export class App {
  // Created at startup so its effect keeps <html data-theme> in step
  // with the theme signals on every route, including the signed-out ones.
  private readonly theme = inject(ThemeService);
}
