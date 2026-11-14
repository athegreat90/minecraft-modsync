import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AdminApi, describeError } from '../../core/api/admin-api';
import { ServerRow } from '../../core/api/models';

/** Server overview: one card per Minecraft server with its publication policy and inventory freshness. */
@Component({
  selector: 'ms-overview',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview.html',
})
export class Overview implements OnInit {
  private readonly api = inject(AdminApi);

  /** Rows once loaded; `null` while loading. */
  protected readonly servers = signal<readonly ServerRow[] | null>(null);
  /** Message shown when loading failed. */
  protected readonly error = signal('');

  ngOnInit(): void {
    this.api.overview().subscribe({
      next: (rows) => this.servers.set(rows),
      error: (failure: unknown) => this.error.set(describeError(failure)),
    });
  }
}
