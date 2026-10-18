import { describe, expect, it } from 'vitest';
import type { OnboardingStatus } from '../native/onboarding';
import {
  describeHomeStatus,
  floatingMicLine,
  MODE_HINT,
  pauseLine,
} from './homeStatus';
import { summarizeReadiness } from './voiceStatus';

const base: OnboardingStatus = {
  microphone: true,
  imeEnabled: true,
  imeSelected: true,
  overlay: true,
  notifications: true,
  overlaySupported: true,
};
const live = { enabled: true, paused: false };
const statusFor = (status: Partial<OnboardingStatus>, settings = live) =>
  describeHomeStatus(summarizeReadiness(settings, { ...base, ...status }));

describe('describeHomeStatus', () => {
  it('waits for the status', () => {
    expect(describeHomeStatus(null)).toBeNull();
  });
  it('says ready with no button', () => {
    expect(statusFor({})).toEqual({
      tone: 'ready',
      line: 'Ready to dictate',
      action: null,
    });
  });
  it('offers Resume when paused', () => {
    expect(statusFor({}, { enabled: true, paused: true })).toEqual({
      tone: 'paused',
      line: 'Paused',
      action: 'resume',
    });
  });
  it('names the missing keyboard step, then the microphone', () => {
    expect(statusFor({ imeEnabled: false, imeSelected: false })?.line).toBe(
      'Enable the Odicto keyboard',
    );
    expect(statusFor({ imeSelected: false })).toEqual({
      tone: 'setup',
      line: 'Select Odicto as your keyboard',
      action: 'fix',
    });
    expect(statusFor({ microphone: false })?.line).toBe(
      'Allow microphone access',
    );
  });
});

describe('row lines', () => {
  it('describes the floating microphone', () => {
    expect(floatingMicLine({ enabled: true, paused: true }, true)).toMatch(
      /Paused/,
    );
    expect(floatingMicLine({ enabled: true, paused: false }, false)).toMatch(
      /permission/,
    );
    expect(floatingMicLine({ enabled: true, paused: false }, undefined)).toBe(
      'On screen over other apps',
    );
    expect(floatingMicLine({ enabled: false, paused: false }, true)).toMatch(
      /within reach/,
    );
    expect(floatingMicLine(null, undefined)).toMatch(/within reach/);
  });
  it('describes pause', () => {
    expect(pauseLine(true)).toMatch(/floating microphone is hidden/);
    expect(pauseLine(false)).toMatch(/Hides the floating microphone/);
  });
  it('has a hint for every mode', () => {
    expect(Object.keys(MODE_HINT).sort()).toEqual(['ai', 'live', 'raw']);
  });
});
