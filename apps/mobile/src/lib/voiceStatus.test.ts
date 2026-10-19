import { describe, expect, it } from 'vitest';
import type { OnboardingStatus } from '../native/onboarding';
import { summarizeReadiness } from './voiceStatus';

const ready: OnboardingStatus = {
  microphone: true,
  imeEnabled: true,
  imeSelected: true,
  overlay: true,
  notifications: true,
  overlaySupported: true,
  clipboardSaving: false,
};

describe('summarizeReadiness', () => {
  it('waits for both inputs', () => {
    expect(summarizeReadiness(null, ready)).toBeNull();
    expect(
      summarizeReadiness({ enabled: false, paused: false }, null),
    ).toBeNull();
  });

  it('is ready when keyboard and microphone are set up', () => {
    const result = summarizeReadiness({ enabled: true, paused: false }, ready);
    expect(result?.tone).toBe('ready');
    expect(result?.items.map((item) => item.state)).toEqual([
      'Ready',
      'Allowed',
      'On',
    ]);
  });

  it('asks to finish setup and names the keyboard problem', () => {
    const result = summarizeReadiness(
      { enabled: false, paused: false },
      { ...ready, imeSelected: false },
    );
    expect(result?.tone).toBe('setup');
    expect(result?.items[0]).toMatchObject({
      state: 'Not selected',
      ok: false,
    });
  });

  it('reports paused before anything else', () => {
    const result = summarizeReadiness(
      { enabled: true, paused: true },
      { ...ready, microphone: false },
    );
    expect(result?.tone).toBe('paused');
    expect(result?.items[2]).toMatchObject({ state: 'Paused', ok: false });
  });

  it('skips the floating microphone when the build has none', () => {
    const result = summarizeReadiness(
      { enabled: false, paused: false },
      { ...ready, overlaySupported: false },
    );
    expect(result?.items).toHaveLength(2);
    expect(result?.detail).toBe('Use the microphone on the Odicto keyboard.');
  });
});
