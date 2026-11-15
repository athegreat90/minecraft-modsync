import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AdminApi, describeError } from '../../core/api/admin-api';
import { UserFilter, UserPage } from '../../core/api/models';

/** Searchable, filterable, paged list of accounts. The filter and the page live in the URL query string. */
@Component({
  selector: 'ms-users-page',
  imports: [ReactiveFormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './users-page.html',
})
export class UsersPage implements OnInit {
  private readonly api = inject(AdminApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  /** Loaded page; `null` while loading. */
  protected readonly result = signal<UserPage | null>(null);
  protected readonly error = signal('');
  protected readonly filter = new FormGroup({
    q: new FormControl('', { nonNullable: true }),
    role: new FormControl<UserFilter['role']>('', { nonNullable: true }),
    status: new FormControl<UserFilter['status']>('', { nonNullable: true }),
  });
  /** Result count for the polite live region; empty until the first page has loaded. */
  protected readonly count = computed(() => {
    const total = this.result()?.total;
    return total === undefined ? '' : `${total} ${total === 1 ? 'account' : 'accounts'}`;
  });

  ngOnInit(): void {
    const params = this.route.snapshot.queryParamMap;
    const role = params.get('role');
    const status = params.get('status');
    const page = Number.parseInt(params.get('page') ?? '', 10);
    this.filter.setValue({
      q: params.get('q') ?? '',
      role: role === 'ADMIN' || role === 'CLIENT' ? role : '',
      status: status === 'enabled' || status === 'disabled' ? status : '',
    });
    this.load(Number.isInteger(page) && page > 0 ? page : 0);
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
    const filter = this.filter.getRawValue();
    // Replace the history entry so paging and searching do not flood the Back button; empty values stay out of the URL.
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { q: filter.q || null, role: filter.role || null, status: filter.status || null, page: page || null },
      replaceUrl: true,
    });
    this.api.users({ ...filter, page }).subscribe({
      next: (result) => this.result.set(result),
      error: (failure: unknown) => this.error.set(describeError(failure)),
    });
  }
}
