import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { readXsrfToken } from '../../core/auth/csrf';
import { SessionService } from '../../core/auth/session';
import { ThemeSwitcher } from '../components/theme-switcher/theme-switcher';

/** Authenticated page frame: top bar, navigation drawer below `lg`, sidebar from `lg` up. */
@Component({
  selector: 'ms-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet, ThemeSwitcher],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shell.html',
})
export class Shell {
  protected readonly sessions = inject(SessionService);
  /** Value for the sign-out form's hidden `_csrf` field. */
  protected readonly csrf = readXsrfToken(document.cookie);
  /** Whether the navigation drawer is open (compact screens only). */
  protected readonly drawerOpen = signal(false);

  /** Opens or closes the drawer. */
  protected toggleDrawer(): void {
    this.drawerOpen.update((open) => !open);
  }

  /** Closes the drawer, used after navigating. */
  protected closeDrawer(): void {
    this.drawerOpen.set(false);
  }
}
