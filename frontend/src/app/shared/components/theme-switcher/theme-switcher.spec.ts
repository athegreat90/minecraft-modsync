import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { ThemeService } from '../../../core/theme/theme.service';
import { ThemeSwitcher } from './theme-switcher';

@Component({ imports: [ThemeSwitcher], template: '<ms-theme-switcher [compact]="compact" />' })
class Host {
  compact = false;
}

afterEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute('data-theme');
  document.documentElement.removeAttribute('data-bs-theme');
});

function render(compact = false): { el: HTMLElement; radios: HTMLInputElement[]; themes: ThemeService; refresh: () => void } {
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.compact = compact;
  fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  return { el, radios: Array.from(el.querySelectorAll<HTMLInputElement>('input[type="radio"]')), themes: TestBed.inject(ThemeService), refresh: () => fixture.detectChanges() };
}

describe('ThemeSwitcher', () => {
  it('offers both themes as one radio group with the active one checked', () => {
    const { radios, el } = render();

    expect(radios.map((r) => r.value)).toEqual(['dark', 'amoled']);
    expect(new Set(radios.map((r) => r.name)).size).toBe(1);
    expect(radios.map((r) => r.checked)).toEqual([true, false]);
    expect(el.querySelector('fieldset legend')?.textContent).toBe('Theme');
    expect(Array.from(el.querySelectorAll('label')).map((l) => l.textContent?.trim())).toEqual(['Valhalla Dark', 'AMOLED Black']);
  });

  it('uses short visible labels and a hidden legend when compact', () => {
    const { el } = render(true);

    expect(Array.from(el.querySelectorAll('label')).map((l) => l.textContent?.trim())).toEqual(['Dark', 'AMOLED']);
    expect(el.querySelector('legend')?.classList.contains('visually-hidden')).toBe(true);
  });

  it('switches the theme when a radio is chosen', () => {
    const { radios, themes, refresh } = render();

    radios[1].click();
    refresh();

    expect(themes.theme()).toBe('amoled');
    expect(radios.map((r) => r.checked)).toEqual([false, true]);
  });

  it('keeps several switchers on one page independent', () => {
    const a = render();
    const b = render();

    expect(a.radios[0].name).not.toBe(b.radios[0].name);
  });
});
