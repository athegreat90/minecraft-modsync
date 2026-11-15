import { ChangeDetectionStrategy, Component, ElementRef, Injector, OnInit, afterNextRender, computed, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable } from 'rxjs';
import { AdminApi, describeError } from '../../core/api/admin-api';
import { runCommand } from '../../core/api/run-command';
import {
  CLASSIFICATIONS,
  Classification,
  EntryRow,
  POLICIES,
  PublicationPolicy,
  ServerPage,
} from '../../core/api/models';

/** Rows per page in the mods table. */
const MODS_PAGE_SIZE = 25;

/** One server: publication policy, history, client coverage, scanned mods with classification and the latest manifest. */
@Component({
  selector: 'ms-server-page',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './server-page.html',
})
export class ServerPageView implements OnInit {
  private readonly api = inject(AdminApi);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

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

  /** Case-insensitive file name filter of the mods table. */
  protected readonly filterText = signal('');
  /** When set, the mods table lists only mods nobody classified yet. */
  protected readonly needsReview = signal(false);
  private readonly requestedPage = signal(0);

  /** Mods matching the filter and the "Needs review" toggle. */
  protected readonly filtered = computed(() => {
    const needle = this.filterText().trim().toLowerCase();
    const onlyUnreviewed = this.needsReview();
    return (this.page()?.entries ?? []).filter(
      (e) => (!onlyUnreviewed || e.classification === null) && e.fileName.toLowerCase().includes(needle),
    );
  });
  protected readonly pageCount = computed(() => Math.max(1, Math.ceil(this.filtered().length / MODS_PAGE_SIZE)));
  /** Zero-based page shown; clamped because a reload can shrink the list. */
  protected readonly pageIndex = computed(() => Math.min(this.requestedPage(), this.pageCount() - 1));
  protected readonly visibleMods = computed(() => {
    const start = this.pageIndex() * MODS_PAGE_SIZE;
    return this.filtered().slice(start, start + MODS_PAGE_SIZE);
  });

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

  /** Opens the classification form for one mod and brings it into view, because it sits far from long mod lists. */
  protected select(entry: EntryRow): void {
    this.selected.set(entry);
    this.classifyForm.reset({
      classification: entry.classification && entry.classification !== 'UNKNOWN' ? entry.classification : 'CLIENT_REQUIRED',
      reason: '',
    });
    afterNextRender(
      () => {
        const form = this.host.nativeElement.querySelector<HTMLElement>('#classify-form');
        form?.scrollIntoView({ block: 'center' });
        form?.querySelector<HTMLInputElement>('input[formcontrolname="reason"]')?.focus();
      },
      { injector: this.injector },
    );
  }

  /** Closes the classification form and returns focus to the button that opened it. */
  protected cancel(): void {
    this.closeClassify();
  }

  /** Applies a new file name filter and goes back to the first page. */
  protected setFilter(event: Event): void {
    this.filterText.set((event.target as HTMLInputElement).value);
    this.requestedPage.set(0);
  }

  /** Switches the "Needs review" toggle and goes back to the first page. */
  protected setNeedsReview(event: Event): void {
    this.needsReview.set((event.target as HTMLInputElement).checked);
    this.requestedPage.set(0);
  }

  /** Shows another page of the mods table. */
  protected go(index: number): void {
    this.requestedPage.set(index);
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
      this.closeClassify(),
    );
  }

  private closeClassify(): void {
    const sha = this.selected()?.sha512;
    this.selected.set(null);
    afterNextRender(
      () => {
        const buttons = Array.from(this.host.nativeElement.querySelectorAll<HTMLButtonElement>('button[data-sha]'));
        buttons.find((b) => b.dataset['sha'] === sha)?.focus();
      },
      { injector: this.injector },
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
    runCommand(command, { error: this.error, notice: this.notice }, success, () => {
      done();
      this.load();
    });
  }
}
