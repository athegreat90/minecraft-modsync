import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { AdminApi, describeError } from '../../core/api/admin-api';
import { UserDetail } from '../../core/api/models';

/** One account with its lifecycle actions. Administrator accounts are read-only; the server enforces that too. */
@Component({
  selector: 'ms-user-detail',
  imports: [ReactiveFormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './user-detail.html',
})
export class UserDetailView implements OnInit {
  private readonly api = inject(AdminApi);

  /** Account id taken from the route (`/users/:id`). */
  readonly id = input.required<string>();

  protected readonly detail = signal<UserDetail | null>(null);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly confirmDevices = new FormControl(false, { nonNullable: true, validators: [Validators.requiredTrue] });
  protected readonly password = new FormGroup({
    password: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.minLength(16), Validators.maxLength(256)] }),
    confirmation: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });

  ngOnInit(): void {
    this.load();
  }

  /** Disables the account and ends its sessions. */
  protected disable(): void {
    this.run(this.api.command(this.id(), 'disable'), 'Account disabled.');
  }

  /** Enables the account again. */
  protected enable(): void {
    this.run(this.api.command(this.id(), 'enable'), 'Account enabled.');
  }

  /** Ends API credentials and dashboard sessions. */
  protected revokeSessions(): void {
    this.run(this.api.command(this.id(), 'revoke-sessions'), 'Sessions and tokens ended. Clients must sign in again.');
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

  private load(): void {
    this.api.user(this.id()).subscribe({
      next: (detail) => this.detail.set(detail),
      error: (failure: unknown) => this.error.set(describeError(failure)),
    });
  }

  private run(command: Observable<unknown>, success: string, done: () => void = () => undefined): void {
    this.error.set('');
    this.notice.set('');
    command.subscribe({
      next: () => {
        this.notice.set(success);
        done();
        this.load();
      },
      error: (failure: unknown) => this.error.set(describeError(failure)),
    });
  }
}
