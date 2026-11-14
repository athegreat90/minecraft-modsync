import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { SessionService } from './session';

/** Lets signed-in administrators through and sends everyone else to the login page. */
export const sessionGuard: CanActivateFn = () => {
  const sessions = inject(SessionService);
  const router = inject(Router);
  if (sessions.signedIn()) {
    return true;
  }
  return sessions.refresh().pipe(map((ok) => ok || router.createUrlTree(['/login'])));
};
