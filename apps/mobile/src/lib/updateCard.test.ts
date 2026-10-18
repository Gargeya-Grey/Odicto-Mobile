import { describe, expect, it } from 'vitest';
import type { UpdateStatus } from '../native/update';
import { describeUpdate, updateSettingLine } from './updateCard';

const base: UpdateStatus = {
  enabled: true,
  available: true,
  version: '1.2.0',
  current: '1.1.1',
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
    expect(updateSettingLine(true)).toMatch(/once a day/i);
    expect(updateSettingLine(false)).toMatch(/^Off\./);
  });
});
