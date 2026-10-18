import { describe, expect, it } from 'vitest';
import type { UpdateStatus } from '../native/update';
import {
  checkResultLine,
  describeUpdate,
  updateSettingLine,
} from './updateCard';

const base: UpdateStatus = {
  enabled: true,
  available: true,
  version: '1.2.0',
  current: '1.1.1',
  checkedAt: 1_000,
  failed: false,
};

describe('describeUpdate', () => {
  it('names the new version and the installed one', () => {
    const text = describeUpdate(base);
    expect(text?.title).toBe('Odicto 1.2.0 is available');
    expect(text?.line).toContain('You have 1.1.1.');
    expect(text?.line).toContain('keys and history stay');
  });

  it('shows nothing until an update is known', () => {
    expect(describeUpdate(null)).toBeNull();
    expect(describeUpdate({ ...base, available: false })).toBeNull();
    expect(describeUpdate({ ...base, version: null })).toBeNull();
  });

  it('shows nothing while checking is off', () => {
    expect(describeUpdate({ ...base, enabled: false })).toBeNull();
  });

  it('leaves out the installed version when it is unknown', () => {
    expect(describeUpdate({ ...base, current: '' })?.line).toMatch(
      /^Download it/,
    );
  });
});

describe('updateSettingLine', () => {
  it('says when it checks, and when it does not', () => {
    expect(updateSettingLine(true)).toMatch(/once an hour/i);
    expect(updateSettingLine(false)).toMatch(/^Off\./);
  });
});

describe('checkResultLine', () => {
  const ago = (at: number, now: number) =>
    `${Math.round((now - at) / 1000)} s ago`;
  it('says the app is current, and when it looked', () => {
    expect(
      checkResultLine({ ...base, available: false, version: null }, 6_000, ago),
    ).toBe('You have the latest version. Checked 5 s ago.');
  });
  it('reads naturally when the check was a moment ago', () => {
    const justNow = () => 'Just now';
    expect(
      checkResultLine(
        { ...base, available: false, version: null },
        2_000,
        justNow,
      ),
    ).toBe('You have the latest version. Checked just now.');
  });
  it('names a newer version when one is known', () => {
    expect(checkResultLine(base, 2_000, ago)).toMatch(/1\.2\.0 is available/);
  });
  it('reports a failure instead of implying everything is current', () => {
    const failed = { ...base, failed: true };
    expect(checkResultLine(failed, 2_000, ago)).toMatch(
      /Could not reach GitHub/,
    );
    expect(checkResultLine(failed, 2_000, ago)).toMatch(/Last checked 1 s ago/);
    expect(checkResultLine({ ...failed, checkedAt: 0 }, 2_000, ago)).toMatch(
      /Check your connection/,
    );
  });
  it('covers never checked and switched off', () => {
    expect(checkResultLine({ ...base, checkedAt: 0 }, 2_000, ago)).toBe(
      'Not checked yet.',
    );
    expect(checkResultLine({ ...base, enabled: false }, 2_000, ago)).toMatch(
      /Turn on/,
    );
  });
});
