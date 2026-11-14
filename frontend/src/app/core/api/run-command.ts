import { WritableSignal } from '@angular/core';
import { Observable } from 'rxjs';
import { describeError } from './admin-api';

/** The two messages a page shows about the last command it ran. */
export interface CommandFeedback {
  readonly error: WritableSignal<string>;
  readonly notice: WritableSignal<string>;
}

/**
 * Runs a server command and reports its outcome on the page.
 *
 * Clears the previous messages first. On success it shows `success` and calls `afterSuccess` (usually a reload); on failure it
 * shows a message that is safe for an administrator and does not call `afterSuccess`.
 */
export function runCommand(command: Observable<unknown>, feedback: CommandFeedback, success: string, afterSuccess: () => void): void {
  feedback.error.set('');
  feedback.notice.set('');
  command.subscribe({
    next: () => {
      feedback.notice.set(success);
      afterSuccess();
    },
    error: (failure: unknown) => feedback.error.set(describeError(failure)),
  });
}
