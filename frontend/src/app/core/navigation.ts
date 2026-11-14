import { InjectionToken } from '@angular/core';

/** Performs a full-page navigation, as needed to leave the application for a provider's sign-in page. */
export const ASSIGN_LOCATION = new InjectionToken<(url: string) => void>('ASSIGN_LOCATION', {
  providedIn: 'root',
  factory: () => (url: string) => window.location.assign(url),
});
