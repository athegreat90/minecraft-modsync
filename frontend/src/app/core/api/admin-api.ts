import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Classification,
  IdentitiesView,
  LinkStart,
  Problem,
  ProvidersView,
  PublicationPolicy,
  ServerPage,
  ServerRow,
  UserDetail,
  UserFilter,
  UserPage,
} from './models';

const BASE = '/ui/api';

/** Result of a user command that was applied. */
export interface CommandDone {
  readonly userId: string;
}

/**
 * Typed client of the `/ui/api` administration API.
 *
 * It only moves data: every authorization, validation and business decision is made by the server.
 */
@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);

  /** Lists all servers with their publication policy. */
  overview(): Observable<ServerRow[]> {
    return this.http.get<ServerRow[]>(`${BASE}/overview`);
  }

  /** Loads one server page. */
  server(id: string): Observable<ServerPage> {
    return this.http.get<ServerPage>(`${BASE}/servers/${encodeURIComponent(id)}`);
  }

  /** Changes the automatic publication policy of a server. */
  setPolicy(id: string, policy: PublicationPolicy): Observable<unknown> {
    return this.http.put(`${BASE}/servers/${encodeURIComponent(id)}/publication-policy`, { policy });
  }

  /** Approves the classification of one scanned artifact. */
  classify(id: string, sha512: string, classification: Classification, reason: string): Observable<unknown> {
    return this.http.put(`${BASE}/servers/${encodeURIComponent(id)}/classification`, {
      sha512,
      classification,
      reason,
    });
  }

  /** Requests re-evaluation of automatic publication. */
  reevaluate(id: string): Observable<unknown> {
    return this.http.post(`${BASE}/servers/${encodeURIComponent(id)}/reevaluate`, {});
  }

  /** Searches the user list. */
  users(filter: UserFilter): Observable<UserPage> {
    let params = new HttpParams().set('page', filter.page);
    if (filter.q) {
      params = params.set('q', filter.q);
    }
    if (filter.role) {
      params = params.set('role', filter.role);
    }
    if (filter.status) {
      params = params.set('status', filter.status);
    }
    return this.http.get<UserPage>(`${BASE}/users`, { params });
  }

  /** Loads one account. */
  user(id: string): Observable<UserDetail> {
    return this.http.get<UserDetail>(`${BASE}/users/${encodeURIComponent(id)}`);
  }

  /** Creates a CLIENT account. */
  createUser(username: string, password: string, confirmation: string): Observable<CommandDone> {
    return this.http.post<CommandDone>(`${BASE}/users`, { username, password, confirmation });
  }

  /** Runs a parameterless account command: disable, enable or revoke-sessions. */
  command(id: string, action: 'disable' | 'enable' | 'revoke-sessions'): Observable<CommandDone> {
    return this.http.post<CommandDone>(`${BASE}/users/${encodeURIComponent(id)}/${action}`, {});
  }

  /** Resets a password and ends the account's credentials and sessions. */
  resetPassword(id: string, password: string, confirmation: string): Observable<CommandDone> {
    return this.http.post<CommandDone>(`${BASE}/users/${encodeURIComponent(id)}/password`, {
      password,
      confirmation,
    });
  }

  /** Permanently revokes every device of an account. */
  revokeDevices(id: string): Observable<CommandDone> {
    return this.http.post<CommandDone>(`${BASE}/users/${encodeURIComponent(id)}/revoke-devices`, {
      confirm: true,
    });
  }

  /** Lists the sign-in providers the server has configured; public, used by the login page. */
  providers(): Observable<ProvidersView> {
    return this.http.get<ProvidersView>(`${BASE}/sso/providers`);
  }

  /** Lists the providers linked to the signed-in account and those that can be linked. */
  identities(): Observable<IdentitiesView> {
    return this.http.get<IdentitiesView>(`${BASE}/me/identities`);
  }

  /** Prepares linking a provider; the caller then opens the returned authorization path. */
  startLink(provider: string): Observable<LinkStart> {
    return this.http.post<LinkStart>(`${BASE}/me/identities/${encodeURIComponent(provider)}/link`, {});
  }

  /** Removes a provider link from the signed-in account. */
  unlink(provider: string): Observable<unknown> {
    return this.http.delete(`${BASE}/me/identities/${encodeURIComponent(provider)}`);
  }
}

/** Turns any failed request into a message that is safe to show to an administrator. */
export function describeError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const body = error.error as Partial<Problem> | null;
    if (body && typeof body.message === 'string') {
      return body.message;
    }
    if (error.status === 403) {
      return 'The server refused the request.';
    }
    if (error.status === 409) {
      return 'The server is in maintenance mode; changes are paused.';
    }
    if (error.status === 404) {
      return 'That item no longer exists.';
    }
  }
  return 'Something went wrong. Try again.';
}
