import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable } from 'rxjs';
import { AdminApi, describeError } from '../../core/api/admin-api';
import {
  CLASSIFICATIONS,
  Classification,
  EntryRow,
  POLICIES,
  PublicationPolicy,
  ServerPage,
} from '../../core/api/models';

/** One server: publication policy, history, client coverage, scanned mods with classification and the latest manifest. */
@Component({
  selector: 'ms-server-page',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './server-page.html',
})
export class ServerPageView implements OnInit {
  private readonly api = inject(AdminApi);

  /** Server id taken from the route (`/servers/:id`). */
  readonly id = input.required<string>();

  protected readonly policies = POLICIES;
  protected readonly classifications = CLASSIFICATIONS;
  /** Loaded page; `null` while loading. */
  protected readonly page = signal<ServerPage | null>(null);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  /** The mod currently being classified. */
  protected readonly selected = signal<EntryRow | null>(null);

  protected readonly policyForm = new FormGroup({
    policy: new FormControl<PublicationPolicy>('MANUAL', { nonNullable: true }),
  });
  protected readonly classifyForm = new FormGroup({
    classification: new FormControl<Exclude<Classification, 'UNKNOWN'>>('CLIENT_REQUIRED', { nonNullable: true }),
    reason: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(500)] }),
  });

  ngOnInit(): void {
    this.load();
  }

  /** Saves the chosen publication policy. */
  protected savePolicy(): void {
    this.run(this.api.setPolicy(this.id(), this.policyForm.controls.policy.value), 'Publication policy saved.');
  }

  /** Requests re-evaluation of automatic publication. */
  protected reevaluate(): void {
    this.run(this.api.reevaluate(this.id()), 'Re-evaluation requested.');
  }

  /** Opens the classification form for one mod. */
  protected select(entry: EntryRow): void {
    this.selected.set(entry);
    this.classifyForm.reset({
      classification: entry.classification && entry.classification !== 'UNKNOWN' ? entry.classification : 'CLIENT_REQUIRED',
      reason: '',
    });
  }

  /** Closes the classification form. */
  protected cancel(): void {
    this.selected.set(null);
  }

  /** Approves the classification of the selected mod. */
  protected saveClassification(): void {
    const entry = this.selected();
    if (!entry || this.classifyForm.invalid) {
      this.classifyForm.markAllAsTouched();
      return;
    }
    const { classification, reason } = this.classifyForm.getRawValue();
    this.run(this.api.classify(this.id(), entry.sha512, classification, reason.trim()), 'Classification saved.', () =>
      this.selected.set(null),
    );
  }

  private load(): void {
    this.api.server(this.id()).subscribe({
      next: (page) => {
        this.page.set(page);
        this.policyForm.setValue({ policy: page.policy });
      },
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
