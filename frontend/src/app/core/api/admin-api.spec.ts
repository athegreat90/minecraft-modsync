import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { AdminApi, describeError } from './admin-api';

describe('AdminApi', () => {
  let api: AdminApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(AdminApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('sends only the filters that are set when listing users', () => {
    api.users({ q: 'ann', role: '', status: 'disabled', page: 2 }).subscribe();

    const request = http.expectOne((r) => r.url === '/ui/api/users');
    expect(request.request.params.get('q')).toBe('ann');
    expect(request.request.params.get('status')).toBe('disabled');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.has('role')).toBe(false);
  });

  it('encodes identifiers placed in the path', () => {
    api.user('a/b').subscribe();

    http.expectOne('/ui/api/users/a%2Fb');
  });

  it('puts the policy in the request body', () => {
    api.setPolicy('s1', 'TRUSTED_AUTO').subscribe();

    const request = http.expectOne('/ui/api/servers/s1/publication-policy');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ policy: 'TRUSTED_AUTO' });
  });

  it('always sends the confirmation when revoking devices', () => {
    api.revokeDevices('u1').subscribe();

    expect(http.expectOne('/ui/api/users/u1/revoke-devices').request.body).toEqual({ confirm: true });
  });
});

describe('describeError', () => {
  it('prefers the message the server supplied', () => {
    const error = new HttpErrorResponse({ status: 422, error: { code: 'INVALID_PASSWORD', message: 'Passwords need 16 to 256 characters.' } });

    expect(describeError(error)).toBe('Passwords need 16 to 256 characters.');
  });

  it('explains maintenance mode for a bare 409', () => {
    expect(describeError(new HttpErrorResponse({ status: 409 }))).toContain('maintenance');
  });

  it('falls back to a generic message for anything else', () => {
    expect(describeError(new Error('boom'))).toBe('Something went wrong. Try again.');
  });
});
