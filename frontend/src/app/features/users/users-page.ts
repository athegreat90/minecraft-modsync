import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AdminApi, describeError } from '../../core/api/admin-api';
import { UserFilter, UserPage } from '../../core/api/models';

/** Searchable, filterable, paged list of accounts. */
@Component({
  selector: 'ms-users-page',
  imports: [ReactiveFormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './users-page.html',
})
export class UsersPage implements OnInit {
  private readonly api = inject(AdminApi);

  /** Loaded page; `null` while loading. */
  protected readonly result = signal<UserPage | null>(null);
  protected readonly error = signal('');
  protected readonly filter = new FormGroup({
    q: new FormControl('', { nonNullable: true }),
    role: new FormControl<UserFilter['role']>('', { nonNullable: true }),
    status: new FormControl<UserFilter['status']>('', { nonNullable: true }),
  });

  ngOnInit(): void {
    this.load(0);
  }

  /** Applies the filter from the first page. */
  protected search(): void {
    this.load(0);
  }

  /** Loads another page of the current filter. */
  protected go(page: number): void {
    this.load(page);
  }

  private load(page: number): void {
    this.error.set('');
    this.api.users({ ...this.filter.getRawValue(), page }).subscribe({
      next: (result) => this.result.set(result),
      error: (failure: unknown) => this.error.set(describeError(failure)),
    });
  }
}
