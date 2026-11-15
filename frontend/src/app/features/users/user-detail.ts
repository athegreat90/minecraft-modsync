import { ChangeDetectionStrategy, Component, ElementRef, Injector, OnInit, afterNextRender, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { AdminApi, describeError } from '../../core/api/admin-api';
import { runCommand } from '../../core/api/run-command';
import { UserDetail } from '../../core/api/models';

/** A lifecycle action that asks for confirmation in the page first. */
type PendingAction = 'disable' | 'revoke-sessions';

/** One account with its lifecycle actions. Administrator accounts are read-only; the server enforces that too. */
@Component({
  selector: 'ms-user-detail',
  imports: [ReactiveFormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './user-detail.html',
})
export class UserDetailView implements OnInit {
  private readonly api = inject(AdminApi);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** Account id taken from the route (`/users/:id`). */
  readonly id = input.required<string>();

  protected readonly detail = signal<UserDetail | null>(null);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  /** Action waiting for the administrator's Confirm, or null. */
  protected readonly pending = signal<PendingAction | null>(null);
  protected readonly confirmDevices = new FormControl(false, { nonNullable: true, validators: [Validators.requiredTrue] });
  protected readonly password = new FormGroup({
    password: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.minLength(16), Validators.maxLength(256)] }),
    confirmation: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });

  ngOnInit(): void {
    this.load();
  }

  /** Asks before disabling the account; the first click only opens the confirmation. */
  protected askDisable(): void {
    this.ask('disable');
  }

  /** Enables the account again. */
  protected enable(): void {
    this.run(this.api.command(this.id(), 'enable'), 'Account enabled.');
  }

  /** Asks before ending API credentials and dashboard sessions. */
  protected askRevokeSessions(): void {
    this.ask('revoke-sessions');
  }

  /** Runs the pending action: disables the account and ends its sessions, or ends sessions and tokens only. */
  protected confirm(): void {
    const action = this.pending();
    this.close(action);
    if (action === 'disable') {
      this.run(this.api.command(this.id(), 'disable'), 'Account disabled.');
    } else if (action === 'revoke-sessions') {
      this.run(this.api.command(this.id(), 'revoke-sessions'), 'Sessions and tokens ended. Clients must sign in again.');
    }
  }

  /** Drops the pending action and returns focus to the button that asked. */
  protected cancel(): void {
    this.close(this.pending());
  }

  /** Revokes every device, after the explicit confirmation. */
  protected revokeDevices(): void {
    if (this.confirmDevices.invalid) {
      this.confirmDevices.markAsTouched();
      return;
    }
    this.run(this.api.revokeDevices(this.id()), 'All devices revoked. Clients must register again with a new device id.', () =>
      this.confirmDevices.reset(false),
    );
  }

  /** Resets the password. */
  protected resetPassword(): void {
    if (this.password.invalid) {
      this.password.markAllAsTouched();
      return;
    }
    const { password, confirmation } = this.password.getRawValue();
    this.run(this.api.resetPassword(this.id(), password, confirmation), 'Password changed. Existing sessions and tokens were ended.', () =>
      this.password.reset({ password: '', confirmation: '' }),
    );
  }

  private ask(action: PendingAction): void {
    this.pending.set(action);
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLButtonElement>('#confirm-action')?.focus(), { injector: this.injector });
  }

  private close(action: PendingAction | null): void {
    this.pending.set(null);
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLButtonElement>(`#ask-${action}`)?.focus(), { injector: this.injector });
  }

  private load(): void {
    this.api.user(this.id()).subscribe({
      next: (detail) => this.detail.set(detail),
      error: (failure: unknown) => this.error.set(describeError(failure)),
    });
  }

  private run(command: Observable<unknown>, success: string, done: () => void = () => undefined): void {
    runCommand(command, { error: this.error, notice: this.notice }, success, () => {
      done();
      this.load();
    });
  }
}
