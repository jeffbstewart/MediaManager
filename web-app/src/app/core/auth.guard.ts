import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Two-phase route guard:
 * 1. Authentication — redirects to /login if not authenticated
 * 2. Legal compliance — redirects to /terms if terms acceptance is required
 */
export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  // Phase 1: Authentication
  if (!auth.isAuthenticated()) {
    const refreshed = await auth.tryRefresh();
    if (!refreshed) {
      return router.createUrlTree(['/login'], {
        queryParams: { returnUrl: state.url },
      });
    }
  }

  // Phase 2: Legal compliance
  try {
    const status = await auth.checkLegalStatus();
    if (!status.compliant) {
      return router.createUrlTree(['/terms'], {
        queryParams: { returnUrl: state.url },
      });
    }
  } catch {
    // If the legal check fails, allow through — server-side enforcement is the real gate.
    // A 451 from the server will be caught by the HTTP interceptor.
  }

  return true;
};
