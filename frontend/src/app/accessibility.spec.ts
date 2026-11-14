import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import axe from 'axe-core';
import { beforeEach, describe, expect, it } from 'vitest';
import { Login } from './features/authentication/login';
import { Overview } from './features/dashboard/overview';
import { UserNew } from './features/users/user-new';
import { Shell } from './shared/layouts/shell';

let http: HttpTestingController;

beforeEach(() => {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
  http = TestBed.inject(HttpTestingController);
});

/** Runs axe on the rendered component. Colour contrast needs real layout, so that rule is left to the browser checks. */
async function violations(fixture: ComponentFixture<unknown>): Promise<string[]> {
  const host = fixture.nativeElement as HTMLElement;
  document.body.appendChild(host);
  const result = await axe.run(host, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } });
  host.remove();
  return result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.html).join(' | ')}`);
}

describe('accessibility', () => {
  it('login page has no automatically detectable violations', async () => {
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();
    http.expectOne('/ui/api/sso/providers').flush({ providers: ['google'] });
    await fixture.whenStable();
    fixture.detectChanges();

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
    http.expectOne('/ui/api/overview').flush([
      { info: { id: 's1', name: 'Survival', minecraftVersion: '1.21', loader: 'fabric', loaderVersion: null, inventoryAt: null, publishedRevision: null, inventoryStale: false }, policy: 'MANUAL' },
    ]);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(await violations(fixture)).toEqual([]);
  });

  it('account creation form labels every field', async () => {
    const fixture = TestBed.createComponent(UserNew);
    fixture.detectChanges();

    expect(await violations(fixture)).toEqual([]);
  });
});
