import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';

/** The authenticated administrator as reported by the server. */
export interface Session {
  readonly username: string;
  readonly role: 'ADMIN';
}

/**
 * Presentation-side view of the server session.
 *
 * The server owns authentication and authorization; this service only mirrors
 * `GET /ui/api/session` so templates can render the signed-in state.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly current = signal<Session | null>(null);

  /** The signed-in administrator, or `null` when signed out. */
  readonly session = this.current.asReadonly();
  /** True while a session is known to exist. */
  readonly signedIn = computed(() => this.current() !== null);

  /** Asks the server for the current session and updates the local mirror. */
  refresh(): Observable<boolean> {
    return this.http.get<Session>('/ui/api/session').pipe(
      tap((session) => this.current.set(session)),
      map(() => true),
      catchError(() => {
        this.current.set(null);
        return of(false);
      }),
    );
  }

  /** Forgets the local session after the server has ended it. */
  clear(): void {
    this.current.set(null);
  }
}
