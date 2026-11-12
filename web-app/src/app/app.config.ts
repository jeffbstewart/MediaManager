import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { RouteReuseStrategy, provideRouter, withInMemoryScrolling, withNavigationErrorHandler } from '@angular/router';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';
import { netDiagInterceptor } from './core/net-diag.interceptor';
import { recoverFromStaleChunk } from './core/stale-chunk-recovery';
import { ParamChangeRouteReuseStrategy } from './core/route-reuse';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withNavigationErrorHandler(recoverFromStaleChunk),
      // The document scrolls (not a container), so a forward navigation
      // starts the new view at the top and Back returns to where you were.
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
    ),
    // A view is recreated when its route parameters change (core/route-reuse.ts).
    { provide: RouteReuseStrategy, useClass: ParamChangeRouteReuseStrategy },
    provideHttpClient(withXhr(), withInterceptors([netDiagInterceptor, authInterceptor])),
    provideAnimationsAsync(),
  ],
};
