import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { AdminApi, describeError } from '../../core/api/admin-api';
import { runCommand } from '../../core/api/run-command';
import { IdentitiesView } from '../../core/api/models';
import { ASSIGN_LOCATION } from '../../core/navigation';

/** Display names of the sign-in providers the server may offer. */
const PROVIDER_NAMES: Readonly<Record<string, string>> = { google: 'Google', github: 'GitHub' };

/** Lets the signed-in administrator link or unlink Google and GitHub. The local password always keeps working. */
@Component({
  selector: 'ms-account-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './account-page.html',
})
export class AccountPage implements OnInit {
  private readonly api = inject(AdminApi);
  private readonly assignLocation = inject(ASSIGN_LOCATION);

  protected readonly view = signal<IdentitiesView | null>(null);
  protected readonly error = signal('');
  protected readonly notice = signal('');

  ngOnInit(): void {
    this.load();
  }

  /** Display name of a provider id. */
  protected nameOf(id: string): string {
    return PROVIDER_NAMES[id] ?? id;
  }

  /** Whether a provider is already linked. */
  protected isLinked(id: string): boolean {
    return this.view()?.linked.includes(id) ?? false;
  }

  /** Starts linking: the server remembers the request, then the browser continues at the provider. */
  protected link(id: string): void {
    this.error.set('');
    this.api.startLink(id).subscribe({
      next: (start) => this.assignLocation(start.authorizationUrl),
      error: (failure: unknown) => this.error.set(describeError(failure)),
    });
  }

  /** Removes the link of one provider. */
  protected unlink(id: string): void {
    this.run(this.api.unlink(id), `${this.nameOf(id)} unlinked.`);
  }

  private load(): void {
    this.api.identities().subscribe({
      next: (view) => this.view.set(view),
      error: (failure: unknown) => this.error.set(describeError(failure)),
    });
  }

  private run(command: Observable<unknown>, success: string): void {
    runCommand(command, { error: this.error, notice: this.notice }, success, () => this.load());
  }
}
