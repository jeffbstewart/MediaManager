import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Guard for the /terms route: ensures user is authenticated but does NOT
 * check legal compliance (that would create a redirect loop).
 */
export const termsGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) return true;

  const refreshed = await auth.tryRefresh();
  if (refreshed) return true;

  return router.createUrlTree(['/login']);
};
