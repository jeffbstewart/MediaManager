import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { applyCachedTheme } from './app/core/theme-ids';

// Set the theme before the first render. It has to happen here rather
// than in an inline script in index.html: the CSP's `script-src 'self'`
// forbids inline scripts. ThemeService takes over after bootstrap.
applyCachedTheme(document);

bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));
