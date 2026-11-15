import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { AdminApi } from '../../core/api/admin-api';
import { readXsrfToken } from '../../core/auth/csrf';

/** Display names of the sign-in providers the server may offer. */
const PROVIDER_NAMES: Readonly<Record<string, string>> = { google: 'Google', github: 'GitHub' };

/**
 * Sign-in page.
 *
 * Local credentials are posted as a plain HTML form to the server /login endpoint, and provider buttons are plain links to
 * the server authorization endpoints, so credentials and provider secrets are never handled by script.
 */
@Component({
  selector: 'ms-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './login.scss',
  templateUrl: './login.html',
})
export class Login implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(AdminApi);

  /** Value for the hidden `_csrf` form field. */
  protected readonly csrf = signal(readXsrfToken(document.cookie));
  /** Message derived from the `error`, `logout`, `expired`, `denied`, `csrf` and `sso` markers the server sets. */
  protected readonly notice = signal(this.describe());
  /** Providers offered next to the password form. */
  protected readonly providers = signal<readonly { id: string; name: string }[]>([]);

  ngOnInit(): void {
    this.api.providers().subscribe({
      next: (view) => this.providers.set(view.providers.map((id) => ({ id, name: PROVIDER_NAMES[id] ?? id }))),
      error: () => this.providers.set([]),
    });
  }

  private describe(): string {
    const params = this.route.snapshot.queryParamMap;
    if (params.has('sso')) {
      return 'That account could not be signed in. Sign in with your password, then link Google or GitHub from your account page.';
    }
    if (params.has('error')) {
      return 'Sign-in failed. Check your username and password.';
    }
    if (params.has('denied')) {
      return 'This account is not an administrator. Sign in with an administrator account.';
    }
    if (params.has('csrf')) {
      return 'The page was out of date. Please sign in again.';
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
