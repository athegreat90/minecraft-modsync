import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { THEMES, THEME_STORAGE_KEY } from './theme.model';

// @ts-expect-error The classic script has no type declarations; importing it runs its self-invoking function.
const load = (): Promise<unknown> => import('../../../../public/theme-init.js');

// The script runs before Angular, so its ids and colours are a second copy of THEMES. These tests keep them in step.
const root = document.documentElement;

async function run(): Promise<void> {
  vi.resetModules();
  await load();
}

beforeEach(() => {
  document.head.insertAdjacentHTML('beforeend', '<meta name="theme-color" content="#ffffff">');
});

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
  root.removeAttribute('data-theme');
  root.removeAttribute('data-bs-theme');
  document.querySelector('meta[name="theme-color"]')?.remove();
});

describe('theme-init.js', () => {
  it.each(THEMES)('applies $id with the same colour as THEMES', async (theme) => {
    localStorage.setItem(THEME_STORAGE_KEY, theme.id);

    await run();

    expect(root.getAttribute('data-theme')).toBe(theme.id);
    expect(root.getAttribute('data-bs-theme')).toBe('dark');
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe(theme.themeColor);
  });

  it.each([null, 'light', '', 'constructor', '__proto__'])('falls back to dark for %j', async (stored) => {
    if (stored !== null) {
      localStorage.setItem(THEME_STORAGE_KEY, stored);
    }

    await run();

    expect(root.getAttribute('data-theme')).toBe('dark');
  });

  it('falls back to dark when storage throws', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });

    await run();

    expect(root.getAttribute('data-theme')).toBe('dark');
  });
});
