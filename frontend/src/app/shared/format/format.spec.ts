import { describe, expect, it } from 'vitest';
import { formatTimestamp, humanize } from './format';

describe('formatTimestamp', () => {
  it('drops the microseconds and keeps the seconds', () => {
    const text = formatTimestamp('2026-11-15T16:43:40.123456Z');
    expect(text).not.toContain('123456');
    expect(text).not.toContain('T16');
    expect(text).toMatch(/40/);
  });

  it('shows a dash for an absent value', () => {
    expect(formatTimestamp(null)).toBe('—');
    expect(formatTimestamp(undefined)).toBe('—');
    expect(formatTimestamp('')).toBe('—');
  });

  it('returns text that is not a date unchanged', () => {
    expect(formatTimestamp('t')).toBe('t');
  });
});

describe('humanize', () => {
  it('writes machine names in sentence case', () => {
    expect(humanize('SERVER_ONLY')).toBe('Server only');
    expect(humanize('UP_TO_DATE')).toBe('Up to date');
    expect(humanize('unreviewed')).toBe('Unreviewed');
    expect(humanize('TRUSTED_AUTO')).toBe('Trusted auto');
  });

  it('shows a dash for an absent value', () => {
    expect(humanize(null)).toBe('—');
  });
});
