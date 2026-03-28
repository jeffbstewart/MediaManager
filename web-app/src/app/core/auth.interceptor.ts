import { inject } from '@angular/core';
import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { AuthService } from './auth.service';

/**
 * Attaches the JWT access token as a Bearer header to outgoing API requests.
 * Auth endpoints (/api/v2/auth/) are excluded — they handle their own auth.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  // Don't attach token to auth endpoints (login, refresh, logout, setup, discover)
  if (req.url.startsWith('/api/v2/auth/')) {
    return next(req);
  }

  const auth = inject(AuthService);
  const token = auth.getAccessToken();
  if (token) {
    req = req.clone({
      setHeaders: { Authorization: `Bearer ${token}` },
    });
  }

  return next(req);
};
