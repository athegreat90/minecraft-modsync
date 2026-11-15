import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ASSIGN_LOCATION } from '../core/navigation';
import { AccountPage } from './account/account-page';
import { ServerPageView } from './servers/server-page';
import { UserDetailView } from './users/user-detail';
import { UserNew } from './users/user-new';
import { UsersPage } from './users/users-page';

let http: HttpTestingController;

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
  http = TestBed.inject(HttpTestingController);
});

async function render<T>(type: new () => T, inputs: Record<string, unknown> = {}): Promise<ComponentFixture<T>> {
  const fixture = TestBed.createComponent(type);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  return fixture;
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  await fixture.whenStable();
  fixture.detectChanges();
}

function button(fixture: ComponentFixture<unknown>, label: string): HTMLButtonElement {
  const found = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((b) => b.textContent?.includes(label));
  if (!found) {
    throw new Error(`No button "${label}"`);
  }
  return found;
}

function type(fixture: ComponentFixture<unknown>, selector: string, value: string): void {
  const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(selector)!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

const client = { user: { id: 'u1', username: 'ann', role: 'CLIENT', enabled: true, manageable: true }, activeDevices: 1 };

describe('UserDetailView actions', () => {
  async function open() {
    const fixture = await render(UserDetailView, { id: 'u1' });
    http.expectOne('/ui/api/users/u1').flush(client);
    await settle(fixture);
    return fixture;
  }

  it('disables the account and reloads it', async () => {
    const fixture = await open();
    button(fixture, 'Disable account').click();
    await settle(fixture);
    button(fixture, 'Confirm').click();

    http.expectOne('/ui/api/users/u1/disable').flush({ userId: 'u1' });
    http.expectOne('/ui/api/users/u1').flush({ ...client, user: { ...client.user, enabled: false } });
    await settle(fixture);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Account disabled.');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Enable account');
  });

  it('asks before disabling and sends nothing until Confirm', async () => {
    const fixture = await open();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('[role="alert"]')).toBeNull();

    button(fixture, 'Disable account').click();
    await settle(fixture);

    expect(http.match('/ui/api/users/u1/disable')).toEqual([]);
    expect(root.querySelector('[role="alert"]')?.textContent).toContain('Disable this account? Its sessions end immediately.');
    expect(document.activeElement).toBe(button(fixture, 'Confirm'));
  });

  it('cancels the disable confirmation, sends nothing and returns focus to the button', async () => {
    const fixture = await open();
    button(fixture, 'Disable account').click();
    await settle(fixture);

    button(fixture, 'Cancel').click();
    await settle(fixture);

    expect(http.match('/ui/api/users/u1/disable')).toEqual([]);
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')).toBeNull();
    expect(document.activeElement).toBe(button(fixture, 'Disable account'));
  });

  it('asks before ending sessions and returns focus to the button after Cancel', async () => {
    const fixture = await open();
    button(fixture, 'End sessions and tokens').click();
    await settle(fixture);

    expect(http.match('/ui/api/users/u1/revoke-sessions')).toEqual([]);
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent).toContain(
      'End all sessions and tokens of this account? Clients must sign in again.',
    );
    expect(document.activeElement).toBe(button(fixture, 'Confirm'));

    button(fixture, 'Cancel').click();
    await settle(fixture);

    expect(http.match('/ui/api/users/u1/revoke-sessions')).toEqual([]);
    expect(document.activeElement).toBe(button(fixture, 'End sessions and tokens'));
  });

  it('enables a disabled account', async () => {
    const fixture = await render(UserDetailView, { id: 'u1' });
    http.expectOne('/ui/api/users/u1').flush({ ...client, user: { ...client.user, enabled: false } });
    await settle(fixture);

    button(fixture, 'Enable account').click();

    const enable = http.expectOne('/ui/api/users/u1/enable');
    expect(enable.request.method).toBe('POST');
    enable.flush({ userId: 'u1' });
    http.expectOne('/ui/api/users/u1').flush(client);
  });

  it('ends sessions', async () => {
    const fixture = await open();
    button(fixture, 'End sessions and tokens').click();
    await settle(fixture);
    button(fixture, 'Confirm').click();

    const revoke = http.expectOne('/ui/api/users/u1/revoke-sessions');
    expect(revoke.request.method).toBe('POST');
    revoke.flush({ userId: 'u1' });
    http.expectOne('/ui/api/users/u1').flush(client);
  });

  it('revokes devices once the confirmation is ticked', async () => {
    const fixture = await open();
    const box = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    box.click();
    button(fixture, 'Revoke all devices').click();

    const revoke = http.expectOne('/ui/api/users/u1/revoke-devices');
    expect(revoke.request.body).toEqual({ confirm: true });
    revoke.flush({ userId: 'u1' });
    http.expectOne('/ui/api/users/u1').flush(client);
  });

  it('resets the password only with a valid one and shows server refusals', async () => {
    const fixture = await open();
    button(fixture, 'Change password').click();
    http.expectNone('/ui/api/users/u1/password');

    type(fixture, 'input[formcontrolname="password"]', 'a-brand-new-password-5678');
    type(fixture, 'input[formcontrolname="confirmation"]', 'a-brand-new-password-5678');
    button(fixture, 'Change password').click();
    http.expectOne('/ui/api/users/u1/password').flush({ code: 'PASSWORD_MISMATCH', message: 'The password and its confirmation do not match.' }, { status: 422, statusText: 'Unprocessable' });
    await settle(fixture);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('do not match');
  });
});

describe('ServerPageView actions', () => {
  const page = {
    info: { id: 's1', name: 'Survival', minecraftVersion: '1.21', loader: 'fabric', loaderVersion: '0.16', inventoryAt: '2026-11-14', publishedRevision: 2, inventoryStale: true },
    policy: 'MANUAL',
    entries: [{ fileName: 'a.jar', sha512: 'abc', shortHash: 'abc', classification: 'SERVER_ONLY' }],
    unreviewed: 0,
    manifestRevision: 2,
    manifest: [{ fileName: 'm.jar', projectId: 'p', versionId: 'v', classification: 'CLIENT_REQUIRED' }],
    coverage: { serverId: 's1', manifestRevision: 2, rows: [{ instanceId: 'i', instanceName: 'main', status: 'UP_TO_DATE', appliedRevision: 2, verifiedAt: null, missingCount: 0 }] },
    history: [{ id: 1, scanId: 's', policy: 'MANUAL', outcome: 'PUBLISHED', revision: 2, detail: 'ok', actor: 'a', createdAt: 't' }],
  };

  async function open() {
    const fixture = await render(ServerPageView, { id: 's1' });
    http.expectOne('/ui/api/servers/s1').flush(page);
    await settle(fixture);
    return fixture;
  }

  it('renders history, coverage and manifest sections', async () => {
    const text = ((await open()).nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('Published');
    expect(text).toContain('Up to date');
    expect(text).toContain('m.jar');
    expect(text).toContain('stale');
  });

  it('saves the publication policy and requests re-evaluation', async () => {
    const fixture = await open();
    button(fixture, 'Save').click();
    const policy = http.expectOne('/ui/api/servers/s1/publication-policy');
    expect(policy.request.body).toEqual({ policy: 'MANUAL' });
    policy.flush({});
    http.expectOne('/ui/api/servers/s1').flush(page);

    button(fixture, 'Re-evaluate now').click();
    const reevaluate = http.expectOne('/ui/api/servers/s1/reevaluate');
    expect(reevaluate.request.method).toBe('POST');
    reevaluate.flush({});
    http.expectOne('/ui/api/servers/s1').flush(page);
  });

  it('brings the classification form into view and focuses the reason field', async () => {
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    const fixture = await open();

    button(fixture, 'Classify').click();
    await settle(fixture);

    expect(scroll).toHaveBeenCalledTimes(1);
    expect(document.activeElement?.getAttribute('formcontrolname')).toBe('reason');
  });

  it('classifies a mod with a reason and can cancel', async () => {
    const fixture = await open();
    button(fixture, 'Classify').click();
    fixture.detectChanges();
    button(fixture, 'Cancel').click();
    fixture.detectChanges();
    expect(button(fixture, 'Classify')).toBeTruthy();

    button(fixture, 'Classify').click();
    fixture.detectChanges();
    type(fixture, 'input[formcontrolname="reason"]', 'checked the metadata');
    button(fixture, 'Approve').click();

    const request = http.expectOne('/ui/api/servers/s1/classification');
    expect(request.request.body).toEqual({ sha512: 'abc', classification: 'SERVER_ONLY', reason: 'checked the metadata' });
    request.flush({});
    http.expectOne('/ui/api/servers/s1').flush(page);
  });
});

describe('UserNew', () => {
  it('creates the account and opens it', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = await render(UserNew);
    type(fixture, 'input[formcontrolname="username"]', 'new-player');
    type(fixture, 'input[formcontrolname="password"]', 'a-brand-new-password-5678');
    type(fixture, 'input[formcontrolname="confirmation"]', 'a-brand-new-password-5678');

    button(fixture, 'Create account').click();
    http.expectOne('/ui/api/users').flush({ userId: 'u9' }, { status: 201, statusText: 'Created' });

    expect(navigate).toHaveBeenCalledWith(['/users', 'u9']);
  });

  it('shows the refusal when the username is taken', async () => {
    const fixture = await render(UserNew);
    type(fixture, 'input[formcontrolname="username"]', 'taken');
    type(fixture, 'input[formcontrolname="password"]', 'a-brand-new-password-5678');
    type(fixture, 'input[formcontrolname="confirmation"]', 'a-brand-new-password-5678');

    button(fixture, 'Create account').click();
    http.expectOne('/ui/api/users').flush({ code: 'USERNAME_TAKEN', message: 'That username is already in use.' }, { status: 409, statusText: 'Conflict' });
    await settle(fixture);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('already in use');
  });
});

describe('UsersPage', () => {
  const result = { users: [{ id: 'u1', username: 'ann', role: 'CLIENT', enabled: true, manageable: true }], page: 0, totalPages: 2, total: 30 };

  it('searches with the chosen filter and pages forward', async () => {
    const fixture = await render(UsersPage);
    http.expectOne((r) => r.url === '/ui/api/users').flush(result);
    await settle(fixture);

    type(fixture, 'input[type="search"]', 'an');
    button(fixture, 'Search').click();
    const search = http.expectOne((r) => r.url === '/ui/api/users');
    expect(search.request.params.get('q')).toBe('an');
    search.flush(result);
    await settle(fixture);

    button(fixture, 'Next').click();
    const next = http.expectOne((r) => r.url === '/ui/api/users');
    expect(next.request.params.get('page')).toBe('1');
    next.flush({ ...result, page: 1 });
    await settle(fixture);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Page 2 of 2');
  });

  it('shows the filter from the URL and loads that page', async () => {
    await TestBed.inject(Router).navigateByUrl('/?q=an&role=CLIENT&status=enabled&page=1');
    const fixture = await render(UsersPage);

    const request = http.expectOne((r) => r.url === '/ui/api/users');
    expect(request.request.params.get('q')).toBe('an');
    expect(request.request.params.get('role')).toBe('CLIENT');
    expect(request.request.params.get('status')).toBe('enabled');
    expect(request.request.params.get('page')).toBe('1');
    request.flush({ ...result, page: 1 });
    await settle(fixture);

    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector<HTMLInputElement>('#user-q')?.value).toBe('an');
    expect(root.querySelector<HTMLSelectElement>('#user-role')?.value).toBe('CLIENT');
    expect(root.querySelector<HTMLSelectElement>('#user-status')?.value).toBe('enabled');
  });

  it('loads the unfiltered first page when the URL has no filter', async () => {
    const fixture = await render(UsersPage);

    const request = http.expectOne((r) => r.url === '/ui/api/users');
    expect(request.request.params.keys()).toEqual(['page']);
    expect(request.request.params.get('page')).toBe('0');
    request.flush(result);
    await settle(fixture);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('ann');
  });

  it('writes the filter and the page to the URL, replacing the history entry', async () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate');
    const fixture = await render(UsersPage);
    http.expectOne((r) => r.url === '/ui/api/users').flush(result);
    await settle(fixture);

    type(fixture, 'input[type="search"]', 'an');
    button(fixture, 'Search').click();
    http.expectOne((r) => r.url === '/ui/api/users').flush(result);
    await settle(fixture);

    expect(navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { q: 'an', role: null, status: null, page: null }, replaceUrl: true }));
    expect(router.url).toBe('/?q=an');

    button(fixture, 'Next').click();
    http.expectOne((r) => r.url === '/ui/api/users').flush({ ...result, page: 1 });
    await settle(fixture);

    expect(navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { q: 'an', role: null, status: null, page: 1 }, replaceUrl: true }));
    expect(router.url).toBe('/?q=an&page=1');
  });

  it('counts accounts with the right plural and announces the count politely', async () => {
    const fixture = await render(UsersPage);
    http.expectOne((r) => r.url === '/ui/api/users').flush({ ...result, totalPages: 1, total: 1 });
    await settle(fixture);

    const root = fixture.nativeElement as HTMLElement;
    const live = () => Array.from(root.querySelectorAll('[role="status"]')).map((e) => e.textContent?.trim());
    expect(live()).toContain('1 account');
    expect(root.textContent).not.toContain('1 accounts');

    button(fixture, 'Search').click();
    http.expectOne((r) => r.url === '/ui/api/users').flush(result);
    await settle(fixture);
    expect(live()).toContain('30 accounts');

    button(fixture, 'Search').click();
    http.expectOne((r) => r.url === '/ui/api/users').flush({ users: [], page: 0, totalPages: 0, total: 0 });
    await settle(fixture);
    expect(live()).toContain('0 accounts');
  });

  it('gives the three filter fields an id and a name', async () => {
    const fixture = await render(UsersPage);
    http.expectOne((r) => r.url === '/ui/api/users').flush(result);
    await settle(fixture);

    const root = fixture.nativeElement as HTMLElement;
    const search = root.querySelector<HTMLInputElement>('input[type="search"]')!;
    expect([search.id, search.name, search.autocomplete]).toEqual(['user-q', 'q', 'off']);
    const role = root.querySelector<HTMLSelectElement>('select[formcontrolname="role"]')!;
    expect([role.id, role.name]).toEqual(['user-role', 'role']);
    const status = root.querySelector<HTMLSelectElement>('select[formcontrolname="status"]')!;
    expect([status.id, status.name]).toEqual(['user-status', 'status']);
  });

  it('says so when nothing matches', async () => {
    const fixture = await render(UsersPage);
    http.expectOne((r) => r.url === '/ui/api/users').flush({ users: [], page: 0, totalPages: 0, total: 0 });
    await settle(fixture);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No accounts match.');
  });
});

describe('AccountPage linking', () => {
  it('prepares the link on the server and then opens the provider', async () => {
    const assign = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), { provide: ASSIGN_LOCATION, useValue: assign }],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = await render(AccountPage);
    http.expectOne('/ui/api/me/identities').flush({ linked: [], available: ['google'] });
    await settle(fixture);

    button(fixture, 'Link Google').click();
    http.expectOne('/ui/api/me/identities/google/link').flush({ authorizationUrl: '/oauth2/authorization/google' });

    expect(assign).toHaveBeenCalledWith('/oauth2/authorization/google');
  });
});
