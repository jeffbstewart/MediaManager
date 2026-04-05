import { inject } from '@angular/core';
import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { tap } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Attaches the JWT access token as a Bearer header to outgoing API requests.
 * Auth endpoints (/api/v2/auth/) are excluded — they handle their own auth.
 * On 401 responses from non-auth endpoints, clears auth state and redirects to login.
 * On 451 responses, clears legal status cache and redirects to /terms.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  // Don't attach token to auth endpoints (login, refresh, logout, setup, discover)
  if (req.url.startsWith('/api/v2/auth/')) {
    return next(req);
  }

  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.getAccessToken();
  if (token) {
    req = req.clone({
      setHeaders: { Authorization: `Bearer ${token}` },
    });
  }

  return next(req).pipe(
    tap({
      error: (err: unknown) => {
        if (err instanceof HttpErrorResponse) {
          if (err.status === 401) {
            auth.logout();
          } else if (err.status === 451) {
            // 451 Unavailable For Legal Reasons — terms acceptance required
            auth.clearLegalStatus();
            router.navigate(['/terms'], {
              queryParams: { returnUrl: router.url },
            });
          }
        }
      },
    })
  );
};
