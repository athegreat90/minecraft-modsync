import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { Overview } from './dashboard/overview';
import { ServerPageView } from './servers/server-page';
import { UserDetailView } from './users/user-detail';
import { UserNew } from './users/user-new';

function setup(): HttpTestingController {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
  return TestBed.inject(HttpTestingController);
}

function text(fixture: ComponentFixture<unknown>): string {
  return (fixture.nativeElement as HTMLElement).textContent ?? '';
}

describe('Overview', () => {
  let http: HttpTestingController;
  beforeEach(() => (http = setup()));

  it('shows a card per server with its policy and manifest', async () => {
    const fixture = TestBed.createComponent(Overview);
    fixture.detectChanges();
    http.expectOne('/ui/api/overview').flush([
      {
        info: { id: 's1', name: 'Survival', minecraftVersion: '1.21', loader: 'fabric', loaderVersion: null, inventoryAt: null, publishedRevision: 3, inventoryStale: false },
        policy: 'TRUSTED_AUTO',
      },
    ]);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text(fixture)).toContain('Survival');
    expect(text(fixture)).toContain('TRUSTED_AUTO');
    expect(text(fixture)).toContain('manifest r3');
  });

  it('says so when no server exists', async () => {
    const fixture = TestBed.createComponent(Overview);
    fixture.detectChanges();
    http.expectOne('/ui/api/overview').flush([]);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text(fixture)).toContain('No servers are registered yet');
  });

  it('shows the failure instead of an empty page', async () => {
    const fixture = TestBed.createComponent(Overview);
    fixture.detectChanges();
    http.expectOne('/ui/api/overview').flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
  });
});

describe('UserNew', () => {
  let http: HttpTestingController;
  beforeEach(() => (http = setup()));

  it('does not call the server while the form is invalid', () => {
    const fixture = TestBed.createComponent(UserNew);
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement).querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    http.expectNone('/ui/api/users');
    expect(text(fixture)).toContain('Passwords need 16 to 256 characters.');
  });
});

describe('UserDetailView', () => {
  let http: HttpTestingController;
  beforeEach(() => (http = setup()));

  const detail = { user: { id: 'u1', username: 'ann', role: 'CLIENT', enabled: true, manageable: true }, activeDevices: 2 };

  it('does not revoke devices until the confirmation is ticked', async () => {
    const fixture = TestBed.createComponent(UserDetailView);
    fixture.componentRef.setInput('id', 'u1');
    fixture.detectChanges();
    http.expectOne('/ui/api/users/u1').flush(detail);
    await fixture.whenStable();
    fixture.detectChanges();

    const button = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((b) => b.textContent?.includes('Revoke all devices'))!;
    button.click();

    http.expectNone('/ui/api/users/u1/revoke-devices');
  });

  it('offers no actions for administrator accounts', async () => {
    const fixture = TestBed.createComponent(UserDetailView);
    fixture.componentRef.setInput('id', 'a1');
    fixture.detectChanges();
    http.expectOne('/ui/api/users/a1').flush({ user: { ...detail.user, id: 'a1', role: 'ADMIN', manageable: false }, activeDevices: 0 });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text(fixture)).toContain('cannot be changed from here');
    expect(text(fixture)).not.toContain('Disable account');
  });
});

describe('ServerPageView', () => {
  let http: HttpTestingController;
  beforeEach(() => (http = setup()));

  const page = {
    info: { id: 's1', name: 'Survival', minecraftVersion: '1.21', loader: 'fabric', loaderVersion: null, inventoryAt: '2026-11-14', publishedRevision: null, inventoryStale: false },
    policy: 'MANUAL',
    entries: [{ fileName: 'a.jar', sha512: 'abc', shortHash: 'abc', classification: null }],
    unreviewed: 1,
    manifestRevision: null,
    manifest: [],
    coverage: null,
    history: [],
  };

  it('needs a reason before a classification is sent', async () => {
    const fixture = TestBed.createComponent(ServerPageView);
    fixture.componentRef.setInput('id', 's1');
    fixture.detectChanges();
    http.expectOne('/ui/api/servers/s1').flush(page);
    await fixture.whenStable();
    fixture.detectChanges();

    const classify = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Classify')!;
    classify.click();
    fixture.detectChanges();
    (fixture.nativeElement as HTMLElement).querySelectorAll('form')[1].dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    http.expectNone('/ui/api/servers/s1/classification');
    expect(text(fixture)).toContain('Give a reason');
  });
});
