import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { readXsrfToken } from '../../core/auth/csrf';

/**
 * Local sign-in page.
 *
 * It posts a plain HTML form to the server /login endpoint so credentials
 * are handled by Spring Security, never by script.
 */
@Component({
  selector: 'ms-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './login.scss',
  templateUrl: './login.html',
})
export class Login {
  private readonly route = inject(ActivatedRoute);

  /** Value for the hidden `_csrf` form field. */
  protected readonly csrf = signal(readXsrfToken(document.cookie));
  /** Message derived from the `error`, `logout` and `expired` markers the server sets. */
  protected readonly notice = signal(this.describe());

  private describe(): string {
    const params = this.route.snapshot.queryParamMap;
    if (params.has('error')) {
      return 'Sign-in failed. Check your username and password.';
    }
    if (params.has('expired')) {
      return 'Your session expired. Please sign in again.';
    }
    if (params.has('logout')) {
      return 'You have been signed out.';
    }
    return '';
  }
}
