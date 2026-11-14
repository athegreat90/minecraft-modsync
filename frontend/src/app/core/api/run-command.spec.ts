import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { runCommand } from './run-command';

function feedback() {
  return { error: signal('old error'), notice: signal('old notice') };
}

describe('runCommand', () => {
  it('clears old messages, shows the success text and runs the follow-up', () => {
    const messages = feedback();
    const command = new Subject<unknown>();
    const afterSuccess = vi.fn();

    runCommand(command, messages, 'Saved.', afterSuccess);
    expect(messages.error()).toBe('');
    expect(messages.notice()).toBe('');

    command.next({});
    expect(messages.notice()).toBe('Saved.');
    expect(afterSuccess).toHaveBeenCalledTimes(1);
  });

  it('shows the server refusal and skips the follow-up on failure', () => {
    const messages = feedback();
    const afterSuccess = vi.fn();
    const refusal = new HttpErrorResponse({ status: 422, error: { code: 'X', message: 'Refused.' } });

    runCommand(throwError(() => refusal), messages, 'Saved.', afterSuccess);

    expect(messages.error()).toBe('Refused.');
    expect(messages.notice()).toBe('');
    expect(afterSuccess).not.toHaveBeenCalled();
  });
});
