import { inject } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';
import { finalize } from 'rxjs';
import { NetDiagService } from './net-diag.service';

/**
 * Maintains an in-flight HTTP request counter for NetDiagService. No-op
 * unless `?netdiag=1` was passed at page load — the service's
 * note* methods early-return otherwise, so this is essentially free
 * in normal operation.
 */
export const netDiagInterceptor: HttpInterceptorFn = (req, next) => {
  const diag = inject(NetDiagService);
  diag.noteRequestStart();
  return next(req).pipe(finalize(() => diag.noteRequestEnd()));
};
