import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { SessionService } from '../auth/session';

/** Drops the local session and returns to the login page when the server answers 401. */
export const unauthorizedInterceptor: HttpInterceptorFn = (request, next) => {
  const sessions = inject(SessionService);
  const router = inject(Router);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && request.url !== '/ui/api/session') {
        sessions.clear();
        void router.navigate(['/login']);
      }
      return throwError(() => error);
    }),
  );
};
