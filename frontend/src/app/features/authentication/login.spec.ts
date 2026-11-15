import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { readXsrfToken } from '../../core/auth/csrf';
import { Login } from './login';

describe('readXsrfToken', () => {
  it('extracts the token from the cookie string', () => {
    expect(readXsrfToken('a=1; XSRF-TOKEN=abc%3D; b=2')).toBe('abc=');
  });

  it('returns an empty string when the cookie is absent', () => {
    expect(readXsrfToken('a=1')).toBe('');
  });
});

describe('Login', () => {
  it('posts the credentials form to the server login endpoint', () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();

    const form: HTMLFormElement = fixture.nativeElement.querySelector('form');

    expect(form.getAttribute('action')).toBe('/login');
    expect(form.method).toBe('post');
    expect(form.querySelector('input[name="username"]')).not.toBeNull();
    expect(form.querySelector('input[name="password"]')).not.toBeNull();
  });
});

describe('Login notice roles', () => {
  it.each([
    ['error', 'alert'],
    ['denied', 'alert'],
    ['csrf', 'alert'],
    ['sso', 'alert'],
    ['logout', 'status'],
    ['expired', 'status'],
  ])('announces the %s marker with role="%s"', (marker, role) => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ [marker]: '' }) } } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();
    http.expectOne('/ui/api/sso/providers').flush({ providers: [] });

    expect((fixture.nativeElement as HTMLElement).querySelector('.notice')?.getAttribute('role')).toBe(role);
  });
});

describe('Login notices', () => {
  it.each([
    ['error', 'Sign-in failed. Check your username and password.'],
    ['denied', 'This account is not an administrator. Sign in with an administrator account.'],
    ['csrf', 'The page was out of date. Please sign in again.'],
  ])('shows the message for the %s marker', (marker, message) => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ [marker]: '' }) } } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();
    http.expectOne('/ui/api/sso/providers').flush({ providers: [] });

    expect((fixture.nativeElement as HTMLElement).querySelector('.notice')?.textContent).toContain(message);
  });
});
