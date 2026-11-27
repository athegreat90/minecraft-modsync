import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import axe from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountPage } from './features/account/account-page';
import { Login } from './features/authentication/login';
import { Overview } from './features/dashboard/overview';
import { ServerPageView } from './features/servers/server-page';
import { UserDetailView } from './features/users/user-detail';
import { UserNew } from './features/users/user-new';
import { UsersPage } from './features/users/users-page';
import { THEMES } from './core/theme/theme.model';
import { ThemeService } from './core/theme/theme.service';
import { Shell } from './shared/layouts/shell';

let http: HttpTestingController;

afterEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute('data-theme');
  document.documentElement.removeAttribute('data-bs-theme');
});

/** Runs axe on the rendered component. Colour contrast needs real layout, so that rule is left to the browser checks. */
async function violations(fixture: ComponentFixture<unknown>): Promise<string[]> {
  const host = fixture.nativeElement as HTMLElement;
  document.body.appendChild(host);
  const result = await axe.run(host, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } });
  host.remove();
  return result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.html).join(' | ')}`);
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  await fixture.whenStable();
  fixture.detectChanges();
}

const info = { id: 's1', name: 'Survival', minecraftVersion: '1.21', loader: 'fabric', loaderVersion: null, inventoryAt: '2026-11-14', publishedRevision: 2, inventoryStale: false };

describe.each(THEMES)('accessibility in $label', (theme) => {
  beforeEach(() => {
    Element.prototype.scrollIntoView = vi.fn();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(ThemeService).select(theme.id);
  });

  it('applies the theme to the document', () => {
    expect(document.documentElement.getAttribute('data-theme')).toBe(theme.id);
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('dark');
  });

  it('login page has no automatically detectable violations', async () => {
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();
    http.expectOne('/ui/api/sso/providers').flush({ providers: ['google'] });
    await settle(fixture);

    expect(await violations(fixture)).toEqual([]);
  });

  it('application shell has no violations and a labelled landmark navigation', async () => {
    const fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();

    expect(await violations(fixture)).toEqual([]);
    expect((fixture.nativeElement as HTMLElement).querySelector('nav[aria-label="Main"]')).not.toBeNull();
  });

  it('overview cards have no violations', async () => {
    const fixture = TestBed.createComponent(Overview);
    fixture.detectChanges();
    http.expectOne('/ui/api/overview').flush([{ info: { ...info, publishedRevision: null, inventoryAt: null }, policy: 'MANUAL' }]);
    await settle(fixture);

    expect(await violations(fixture)).toEqual([]);
  });

  it('account page, including the appearance section, has no violations', async () => {
    const fixture = TestBed.createComponent(AccountPage);
    fixture.detectChanges();
    http.expectOne('/ui/api/me/identities').flush({ linked: ['github'], available: ['github', 'google'] });
    await settle(fixture);

    expect(await violations(fixture)).toEqual([]);
  });

  it('users list has no violations', async () => {
    const fixture = TestBed.createComponent(UsersPage);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/ui/api/users').flush({ users: [{ id: 'u1', username: 'ann', role: 'CLIENT', enabled: true, manageable: true }], page: 0, totalPages: 2, total: 30 });
    await settle(fixture);

    expect(await violations(fixture)).toEqual([]);
  });

  it('account detail has no violations', async () => {
    const fixture = TestBed.createComponent(UserDetailView);
    fixture.componentRef.setInput('id', 'u1');
    fixture.detectChanges();
    http.expectOne('/ui/api/users/u1').flush({ user: { id: 'u1', username: 'ann', role: 'CLIENT', enabled: true, manageable: true }, activeDevices: 2 });
    await settle(fixture);

    expect(await violations(fixture)).toEqual([]);
  });

  it('account creation form labels every field', async () => {
    const fixture = TestBed.createComponent(UserNew);
    fixture.detectChanges();

    expect(await violations(fixture)).toEqual([]);
  });

  it('server page has no violations', async () => {
    const fixture = TestBed.createComponent(ServerPageView);
    fixture.componentRef.setInput('id', 's1');
    fixture.detectChanges();
    http.expectOne('/ui/api/servers/s1').flush({
      info,
      policy: 'MANUAL',
      entries: [
        { fileName: 'a.jar', sha512: 'sha1', shortHash: 'h1', classification: 'SERVER_ONLY' },
        { fileName: 'b.jar', sha512: 'sha2', shortHash: 'h2', classification: null },
      ],
      unreviewed: 1,
      manifestRevision: 2,
      manifest: [{ fileName: 'm.jar', projectId: 'p', versionId: 'v', classification: 'CLIENT_REQUIRED' }],
      coverage: { rows: [{ instanceId: 'i1', instanceName: 'Alex PC', status: 'IN_SYNC', appliedRevision: 2, missingCount: 0, verifiedAt: '2026-11-14' }] },
      history: [{ id: 'h1', createdAt: '2026-11-14', outcome: 'PUBLISHED', revision: 2, detail: 'ok', actor: 'system' }],
    });
    await settle(fixture);

    expect(await violations(fixture)).toEqual([]);
  });
});
