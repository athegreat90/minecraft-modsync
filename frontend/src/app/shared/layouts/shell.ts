import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SessionService } from '../../core/auth/session';

/** Authenticated page frame: top bar, navigation drawer on narrow screens, sidebar on wide ones. */
@Component({
  selector: 'ms-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './shell.scss',
  templateUrl: './shell.html',
})
export class Shell {
  protected readonly sessions = inject(SessionService);
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
