import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { describe, expect, it, beforeEach } from 'vitest';
import { SessionService } from './session';
import { sessionGuard } from './session.guard';

describe('SessionService', () => {
  let http: HttpTestingController;
  let sessions: SessionService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    sessions = TestBed.inject(SessionService);
  });

  it('mirrors the server session when the request succeeds', async () => {
    const result = firstValueFrom(sessions.refresh());
    http.expectOne('/ui/api/session').flush({ username: 'admin', role: 'ADMIN' });

    expect(await result).toBe(true);
    expect(sessions.session()?.username).toBe('admin');
    expect(sessions.signedIn()).toBe(true);
  });

  it('is signed out when the server answers 401', async () => {
    const result = firstValueFrom(sessions.refresh());
    http.expectOne('/ui/api/session').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(await result).toBe(false);
    expect(sessions.signedIn()).toBe(false);
  });

  it('clear forgets the session', async () => {
    const result = firstValueFrom(sessions.refresh());
    http.expectOne('/ui/api/session').flush({ username: 'admin', role: 'ADMIN' });
    await result;

    sessions.clear();

    expect(sessions.session()).toBeNull();
  });

  it('guard redirects anonymous visitors to the login page', async () => {
    const outcome = TestBed.runInInjectionContext(() => sessionGuard({} as never, {} as never));
    const pending = firstValueFrom(outcome as ReturnType<SessionService['refresh']>);
    http.expectOne('/ui/api/session').flush(null, { status: 401, statusText: 'Unauthorized' });

    const tree = await pending;

    expect(TestBed.inject(Router).serializeUrl(tree as never)).toBe('/login');
  });

  it('guard admits a signed-in administrator without another request', async () => {
    const first = firstValueFrom(sessions.refresh());
    http.expectOne('/ui/api/session').flush({ username: 'admin', role: 'ADMIN' });
    await first;

    const outcome = TestBed.runInInjectionContext(() => sessionGuard({} as never, {} as never));

    expect(outcome).toBe(true);
    http.verify();
  });
});
