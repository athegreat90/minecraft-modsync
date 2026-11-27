import { ChangeDetectionStrategy, Component, booleanAttribute, inject, input } from '@angular/core';
import { THEMES } from '../../../core/theme/theme.model';
import { ThemeService } from '../../../core/theme/theme.service';

let instances = 0;

/**
 * Radio-group theme picker. Native radios give arrow-key navigation and a real selected state to assistive
 * technology; the filled button also shows the selection without relying on colour.
 */
@Component({
  selector: 'ms-theme-switcher',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './theme-switcher.html',
})
export class ThemeSwitcher {
  protected readonly themes = THEMES;
  protected readonly themeService = inject(ThemeService);
  /** Radios of one group share a name; the counter keeps several switchers on a page independent. */
  protected readonly group = `ms-theme-${instances++}`;

  /** Short labels and a hidden legend, for the header and the login card. */
  readonly compact = input(false, { transform: booleanAttribute });
}
