import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Only allows access to /setup when the server reports setup_required. */
export const setupGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  try {
    const discover = await auth.discover();
    if (discover.setup_required) {
      return true;
    }
  } catch {
    // If we can't reach the server, don't allow setup
  }

  return router.createUrlTree(['/login']);
};
