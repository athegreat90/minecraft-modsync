import { DOCUMENT } from '@angular/common';
import { Injectable, inject, signal } from '@angular/core';
import { DEFAULT_THEME, THEMES, THEME_STORAGE_KEY, ThemeId, isThemeId } from './theme.model';

/**
 * Owns the selected theme: validates it, mirrors it onto the root element, persists it and follows other tabs.
 *
 * `public/theme-init.js` has already set the root attributes before Angular starts, so this service adopts them
 * instead of deciding again. Both Bootstrap's `data-bs-theme` and the application's `data-theme` are written, so
 * every Bootstrap component inherits the theme. Persistence is client-only; nothing is sent to the server.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  private readonly current = signal<ThemeId>(this.initial());

  /** The active theme. */
  readonly theme = this.current.asReadonly();

  constructor() {
    this.apply(this.current());
    this.document.defaultView?.addEventListener('storage', (event) => {
      // Another tab chose a theme: follow it without writing it back.
      if (event.key === THEME_STORAGE_KEY && isThemeId(event.newValue) && event.newValue !== this.current()) {
        this.current.set(event.newValue);
        this.apply(event.newValue);
      }
    });
  }

  /** Switches theme immediately and remembers the choice; unknown ids and repeats are ignored. */
  select(id: ThemeId): void {
    if (!isThemeId(id) || id === this.current()) {
      return;
    }
    this.current.set(id);
    this.apply(id);
    try {
      this.document.defaultView?.localStorage.setItem(THEME_STORAGE_KEY, id);
    } catch {
      // Storage is unavailable or full: the choice still applies for this page view.
    }
  }

  private initial(): ThemeId {
    const attribute = this.document.documentElement.getAttribute('data-theme');
    if (isThemeId(attribute)) {
      return attribute;
    }
    try {
      const stored = this.document.defaultView?.localStorage.getItem(THEME_STORAGE_KEY);
      return isThemeId(stored) ? stored : DEFAULT_THEME;
    } catch {
      return DEFAULT_THEME;
    }
  }

  private apply(id: ThemeId): void {
    const root = this.document.documentElement;
    if (root.getAttribute('data-theme') !== id) {
      root.setAttribute('data-theme', id);
    }
    // Both themes are dark; Bootstrap's own colour mode stays on dark.
    if (root.getAttribute('data-bs-theme') !== 'dark') {
      root.setAttribute('data-bs-theme', 'dark');
    }
    const color = THEMES.find((theme) => theme.id === id)?.themeColor;
    const meta = this.document.querySelector('meta[name="theme-color"]');
    if (color && meta?.getAttribute('content') !== color) {
      meta?.setAttribute('content', color);
    }
  }
}
