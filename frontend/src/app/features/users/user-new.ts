import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AdminApi, describeError } from '../../core/api/admin-api';

/** Form that creates a CLIENT account. Validation here is only a convenience; the server decides. */
@Component({
  selector: 'ms-user-new',
  imports: [ReactiveFormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './user-new.html',
})
export class UserNew {
  private readonly api = inject(AdminApi);
  private readonly router = inject(Router);

  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    username: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(/^[A-Za-z0-9_.-]{1,100}$/)],
    }),
    password: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.minLength(16), Validators.maxLength(256)] }),
    confirmation: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });

  /** Submits the form and opens the new account. */
  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { username, password, confirmation } = this.form.getRawValue();
    this.error.set('');
    this.api.createUser(username, password, confirmation).subscribe({
      next: (done) => void this.router.navigate(['/users', done.userId]),
      error: (failure: unknown) => this.error.set(describeError(failure)),
    });
  }
}
