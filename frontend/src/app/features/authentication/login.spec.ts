import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
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
