import { Pipe, PipeTransform } from '@angular/core';

/** Local date and time to the second, for example "Nov 15, 2026, 4:43:40 PM". */
const TIMESTAMP = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' });

/**
 * Formats an ISO instant for people: local time, seconds, no microseconds.
 * Absent values show an em dash, and text that is not a date is returned unchanged so nothing is ever hidden.
 */
export function formatTimestamp(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  const instant = new Date(value);
  return Number.isNaN(instant.getTime()) ? value : TIMESTAMP.format(instant);
}

/** Turns a machine name such as `SERVER_ONLY` or `unreviewed` into sentence case: "Server only", "Unreviewed". */
export function humanize(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  const words = value.replace(/[_-]+/g, ' ').trim().toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** Template form of {@link formatTimestamp}. */
@Pipe({ name: 'msTimestamp' })
export class TimestampPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return formatTimestamp(value);
  }
}

/** Template form of {@link humanize}. */
@Pipe({ name: 'msLabel' })
export class LabelPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return humanize(value);
  }
}
