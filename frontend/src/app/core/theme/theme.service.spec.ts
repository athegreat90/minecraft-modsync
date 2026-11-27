import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { THEME_STORAGE_KEY } from './theme.model';
import { ThemeService } from './theme.service';

const root = document.documentElement;

function service(): ThemeService {
  return TestBed.inject(ThemeService);
}

beforeEach(() => {
  document.head.insertAdjacentHTML('beforeend', '<meta name="theme-color" content="#0b0f0e">');
});

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
  root.removeAttribute('data-theme');
  root.removeAttribute('data-bs-theme');
  document.querySelector('meta[name="theme-color"]')?.remove();
});

describe('ThemeService', () => {
  it('starts on Valhalla Dark when nothing is stored', () => {
    expect(service().theme()).toBe('dark');
    expect(root.getAttribute('data-theme')).toBe('dark');
    expect(root.getAttribute('data-bs-theme')).toBe('dark');
  });

  it('adopts the theme the init script already put on the document', () => {
    root.setAttribute('data-theme', 'amoled');

    expect(service().theme()).toBe('amoled');
  });

  it('restores the stored theme when the document carries none', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'amoled');

    expect(service().theme()).toBe('amoled');
    expect(root.getAttribute('data-theme')).toBe('amoled');
  });

  it.each(['light', '', 'AMOLED', '{"a":1}'])('ignores the invalid stored value %j', (value) => {
    localStorage.setItem(THEME_STORAGE_KEY, value);

    expect(service().theme()).toBe('dark');
  });

  it('switches immediately, keeps Bootstrap on its dark mode, updates theme-color and persists', () => {
    const themes = service();

    themes.select('amoled');

    expect(themes.theme()).toBe('amoled');
    expect(root.getAttribute('data-theme')).toBe('amoled');
    expect(root.getAttribute('data-bs-theme')).toBe('dark');
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe('#000000');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('amoled');
  });

  it('ignores an unknown id and does not write storage for a repeated one', () => {
    const themes = service();
    const write = vi.spyOn(Storage.prototype, 'setItem');

    themes.select('neon' as never);
    themes.select('dark');

    expect(themes.theme()).toBe('dark');
    expect(write).not.toHaveBeenCalled();
  });

  it('still applies the theme when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const themes = service();

    themes.select('amoled');

    expect(themes.theme()).toBe('amoled');
    expect(root.getAttribute('data-theme')).toBe('amoled');
  });

  it('follows a change made in another tab without writing it back', () => {
    const themes = service();
    const write = vi.spyOn(Storage.prototype, 'setItem');

    window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: 'amoled' }));

    expect(themes.theme()).toBe('amoled');
    expect(root.getAttribute('data-theme')).toBe('amoled');
    expect(write).not.toHaveBeenCalled();
  });

  it('ignores storage events for other keys and for invalid values', () => {
    const themes = service();

    window.dispatchEvent(new StorageEvent('storage', { key: 'other', newValue: 'amoled' }));
    window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: 'neon' }));
    window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: null }));

    expect(themes.theme()).toBe('dark');
  });
});
