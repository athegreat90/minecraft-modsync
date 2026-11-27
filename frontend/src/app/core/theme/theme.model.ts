/**
 * The selectable themes. Adding a theme means adding an entry here and a matching `styles/themes/_<id>.scss` map;
 * `public/theme-init.js` must list the same ids (a spec keeps the two in step).
 */
export const THEMES = [
  { id: 'dark', label: 'Valhalla Dark', short: 'Dark', themeColor: '#0b0f0e' },
  { id: 'amoled', label: 'AMOLED Black', short: 'AMOLED', themeColor: '#000000' },
] as const;

export type ThemeId = (typeof THEMES)[number]['id'];

/** Valhalla Dark is the default and the fallback for any missing or unknown stored value. */
export const DEFAULT_THEME: ThemeId = 'dark';

/** Namespaced `localStorage` key; `public/theme-init.js` reads the same key before the first paint. */
export const THEME_STORAGE_KEY = 'modsync.theme';

/** Narrows an untrusted value (storage, DOM attribute) to a known theme id. */
export function isThemeId(value: unknown): value is ThemeId {
  return THEMES.some((theme) => theme.id === value);
}
