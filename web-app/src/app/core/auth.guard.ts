import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Redirects to /login if not authenticated. */
export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }

  // Try silent refresh (HttpOnly cookie may still be valid)
  const refreshed = await auth.tryRefresh();
  if (refreshed) {
    return true;
  }

  return router.createUrlTree(['/login']);
};
