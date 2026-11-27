import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ServerPageView } from './server-page';

let http: HttpTestingController;

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
  http = TestBed.inject(HttpTestingController);
});

function entry(index: number, classification: string | null = 'SERVER_ONLY') {
  const name = `mod-${String(index).padStart(2, '0')}.jar`;
  return { fileName: name, sha512: `sha${index}`, shortHash: `h${index}`, classification };
}

function pageWith(entries: ReturnType<typeof entry>[], manifestSize = 2) {
  return {
    info: { id: 's1', name: 'Survival', minecraftVersion: '1.21', loader: 'fabric', loaderVersion: null, inventoryAt: '2026-11-14', publishedRevision: 2, inventoryStale: false },
    policy: 'MANUAL',
    entries,
    unreviewed: entries.filter((e) => e.classification === null).length,
    manifestRevision: 2,
    manifest: Array.from({ length: manifestSize }, (_, i) => ({ fileName: `m${i}.jar`, projectId: 'p', versionId: 'v', classification: 'CLIENT_REQUIRED' })),
    coverage: null,
    history: [],
  };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  await fixture.whenStable();
  fixture.detectChanges();
}

async function open(entries: ReturnType<typeof entry>[], manifestSize = 2): Promise<ComponentFixture<ServerPageView>> {
  const fixture = TestBed.createComponent(ServerPageView);
  fixture.componentRef.setInput('id', 's1');
  fixture.detectChanges();
  http.expectOne('/ui/api/servers/s1').flush(pageWith(entries, manifestSize));
  await settle(fixture);
  return fixture;
}

function root(fixture: ComponentFixture<unknown>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

/** File names shown in the "Server mods" table. */
function modRows(fixture: ComponentFixture<unknown>): string[] {
  return Array.from(root(fixture).querySelectorAll('#mods-table tbody td[data-label="File"]')).map((td) => td.textContent?.trim() ?? '');
}

function button(fixture: ComponentFixture<unknown>, label: string): HTMLButtonElement {
  const found = Array.from(root(fixture).querySelectorAll('button')).find((b) => b.textContent?.trim() === label);
  if (!found) {
    throw new Error(`No button "${label}"`);
  }
  return found;
}

function classifyButtons(fixture: ComponentFixture<unknown>): HTMLButtonElement[] {
  return Array.from(root(fixture).querySelectorAll<HTMLButtonElement>('button')).filter((b) => b.textContent?.trim() === 'Classify');
}

function filterBy(fixture: ComponentFixture<unknown>, value: string): void {
  const input = root(fixture).querySelector<HTMLInputElement>('input#mods-filter')!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

function toggleNeedsReview(fixture: ComponentFixture<unknown>): void {
  root(fixture).querySelector<HTMLInputElement>('input#mods-needs-review')!.click();
}

function pagerText(fixture: ComponentFixture<unknown>): string {
  return root(fixture).querySelector('nav[aria-label="Pages"]')?.textContent ?? '';
}

const sixty = Array.from({ length: 60 }, (_, i) => entry(i + 1));

describe('ServerPageView mods table', () => {
  it('still lists every entry when nothing is filtered', async () => {
    const fixture = await open([entry(1), entry(2), entry(3)]);

    expect(modRows(fixture)).toEqual(['mod-01.jar', 'mod-02.jar', 'mod-03.jar']);
    expect(root(fixture).querySelector('#mods-table')?.classList.contains('ms-table-stack')).toBe(true);
    expect(root(fixture).querySelector('nav[aria-label="Pages"]')).toBeNull();
  });

  it('filters by file name ignoring case and announces the match count', async () => {
    const fixture = await open([entry(1), { ...entry(2), fileName: 'Sodium-Fabric.jar' }, { ...entry(3), fileName: 'sodium-extra.jar' }]);

    filterBy(fixture, 'SODIUM');
    fixture.detectChanges();

    expect(modRows(fixture)).toEqual(['Sodium-Fabric.jar', 'sodium-extra.jar']);
    const live = root(fixture).querySelector('[role="status"]#mods-count');
    expect(live?.textContent?.trim()).toBe('Showing 2 of 3 mods');
  });

  it('has a labelled search field for the filter', async () => {
    const fixture = await open([entry(1)]);

    const input = root(fixture).querySelector<HTMLInputElement>('input#mods-filter')!;
    expect(input.type).toBe('search');
    expect(input.name).toBe('filter');
    expect(root(fixture).querySelector('label[for="mods-filter"]')?.textContent).toContain('Filter mods');
  });

  it('shows classifications and review state as readable labels', async () => {
    const fixture = await open([entry(1, null), entry(2, 'SERVER_ONLY')]);

    const badges = Array.from(root(fixture).querySelectorAll('#mods-table .badge')).map((b) => b.textContent?.trim());
    expect(badges).toEqual(['Unreviewed', 'Server only']);
    const options = Array.from(root(fixture).querySelectorAll('select option')).map((o) => o.textContent?.trim());
    expect(options).toContain('Trusted auto');
    expect(options).not.toContain('TRUSTED_AUTO');
  });

  it('shows only unreviewed mods when Needs review is ticked', async () => {
    const fixture = await open([entry(1, null), entry(2), entry(3, null)]);

    toggleNeedsReview(fixture);
    fixture.detectChanges();

    expect(modRows(fixture)).toEqual(['mod-01.jar', 'mod-03.jar']);
    expect(root(fixture).querySelector('#mods-count')?.textContent?.trim()).toBe('Showing 2 of 3 mods');

    toggleNeedsReview(fixture);
    fixture.detectChanges();
    expect(modRows(fixture)).toHaveLength(3);
  });

  it('pages in blocks of 25 and goes back to page 1 when the filter or toggle changes', async () => {
    const fixture = await open(sixty);

    expect(modRows(fixture)).toHaveLength(25);
    expect(modRows(fixture)[0]).toBe('mod-01.jar');
    expect(pagerText(fixture)).toContain('Page 1 of 3');
    expect(button(fixture, 'Previous').disabled).toBe(true);

    button(fixture, 'Next').click();
    fixture.detectChanges();
    expect(modRows(fixture)[0]).toBe('mod-26.jar');
    expect(pagerText(fixture)).toContain('Page 2 of 3');

    filterBy(fixture, 'mod-');
    fixture.detectChanges();
    expect(modRows(fixture)[0]).toBe('mod-01.jar');
    expect(pagerText(fixture)).toContain('Page 1 of 3');

    button(fixture, 'Next').click();
    fixture.detectChanges();
    expect(pagerText(fixture)).toContain('Page 2 of 3');
    toggleNeedsReview(fixture);
    fixture.detectChanges();
    expect(modRows(fixture)).toEqual([]);
    expect(root(fixture).textContent).toContain('No mods match.');
  });

  it('keeps the latest manifest collapsed behind a summary with its rows intact', async () => {
    const fixture = await open([entry(1)], 94);

    const details = root(fixture).querySelector('details')!;
    expect(details.open).toBe(false);
    expect(details.querySelector('summary')?.textContent?.trim()).toBe('Latest manifest (94 artifacts)');
    expect(details.querySelectorAll('table.ms-table-stack tbody tr')).toHaveLength(94);
    expect(details.querySelector('td[data-label="Project"]')).not.toBeNull();
  });
});

describe('ServerPageView classify focus', () => {
  it('opens a labelled form and focuses the reason field', async () => {
    const fixture = await open([entry(1, null)]);

    button(fixture, 'Classify').click();
    await settle(fixture);

    expect(root(fixture).querySelector('#classify-form label')?.textContent).toContain('Classification');
    expect(document.activeElement?.getAttribute('formcontrolname')).toBe('reason');
  });

  it('returns focus to the Classify button of the row after Cancel', async () => {
    const fixture = await open([entry(1), entry(2), entry(3)]);
    document.body.appendChild(root(fixture));

    classifyButtons(fixture)[1].click();
    await settle(fixture);
    button(fixture, 'Cancel').click();
    await settle(fixture);

    expect(document.activeElement?.tagName).toBe('BUTTON');
    expect(document.activeElement?.getAttribute('data-sha')).toBe('sha2');
  });

  it('returns focus to the Classify button after a successful Approve', async () => {
    const entries = [entry(1), entry(2)];
    const fixture = await open(entries);
    document.body.appendChild(root(fixture));
    classifyButtons(fixture)[1].click();
    await settle(fixture);
    const reason = root(fixture).querySelector<HTMLInputElement>('input[formcontrolname="reason"]')!;
    reason.value = 'checked';
    reason.dispatchEvent(new Event('input'));

    button(fixture, 'Approve').click();
    http.expectOne('/ui/api/servers/s1/classification').flush({});
    http.expectOne('/ui/api/servers/s1').flush(pageWith(entries));
    await settle(fixture);

    expect(document.activeElement?.getAttribute('data-sha')).toBe('sha2');
  });
});

describe('ServerPageView loading', () => {
  it('shows an announced skeleton placeholder while the server loads', () => {
    const fixture = TestBed.createComponent(ServerPageView);
    fixture.componentRef.setInput('id', 's1');
    fixture.detectChanges();

    expect(root(fixture).querySelector('[role="status"]')?.textContent).toContain('Loading server…');
    expect(root(fixture).querySelector('.placeholder-glow')).not.toBeNull();
    http.expectOne('/ui/api/servers/s1');
  });
});
