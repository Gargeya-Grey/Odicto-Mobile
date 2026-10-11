import type { PluginListenerHandle } from '@capacitor/core';
import { useCallback, useEffect, useRef, useState } from 'react';
import { voice, type VoiceSettings } from '../../native/voice';
import { onboarding, type OnboardingStatus } from '../../native/onboarding';
import { createSerialQueue } from '../../lib/serialQueue';

export const DEFAULT_OPENROUTER_MODEL = 'poolside/laguna-xs-2.1:free';

export type KeyName = 'geminiKey' | 'groqKey' | 'openrouterKey';
export type KeyValues = Partial<Record<KeyName, string>>;
type NativePatch = Partial<VoiceSettings> & KeyValues;

/** Text settings that wait for an explicit save. */
export const FORM_FIELDS = [
  'provider',
  'model',
  'liveModel',
  'systemPrompt',
  'polishModel',
  'polishSystemPrompt',
] as const;
export type FormField = (typeof FORM_FIELDS)[number];
export type FormValues = Pick<VoiceSettings, FormField>;

export type Notice = { id: number; text: string; tone: 'info' | 'error' };

const KEY_FLAG: Record<KeyName, keyof VoiceSettings> = {
  geminiKey: 'geminiKeySet',
  groqKey: 'groqKeySet',
  openrouterKey: 'openrouterKeySet',
};

function normalize(value: VoiceSettings): VoiceSettings {
  const model = value.model ?? '';
  if (
    value.openrouterKeySet &&
    value.provider === 'openrouter' &&
    (model === 'openrouter/free' || model.toLowerCase().includes('muse-spark'))
  ) {
    return { ...value, model: DEFAULT_OPENROUTER_MODEL };
  }
  return value;
}

function errorText(error: unknown) {
  return error instanceof Error && error.message
    ? error.message
    : 'Could not save. Try again.';
}

/** Haptic tap. Never throws and never blocks the screen. */
export function tap() {
  try {
    void voice.feedback().catch(() => undefined);
  } catch {
    // Haptics are a nicety; the change itself already happened.
  }
}

/**
 * Settings that feel instant.
 *
 * - `update` changes the screen at once, then sends the save. Saves go out in
 *   the order they were made, one at a time, so a fast double tap cannot
 *   reorder writes. If a save fails, only that field goes back to the last
 *   value the phone confirmed, and one error appears.
 * - `saveForm` is the explicit save for text fields and keys. Key values pass
 *   straight to `voice.save` and are never kept here.
 */
export function useVoiceSettings() {
  const [settings, setSettings] = useState<VoiceSettings | null>(null);
  const [onboardingStatus, setOnboardingStatus] =
    useState<OnboardingStatus | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [saving, setSaving] = useState(false);
  const [openSettingsRequested, setOpenSettingsRequested] = useState(0);

  const [enqueue] = useState(createSerialQueue);
  const confirmed = useRef<VoiceSettings | null>(null);
  const mutations = useRef(0);
  const pending = useRef(0);
  const fieldOwner = useRef<Partial<Record<keyof VoiceSettings, number>>>({});
  const noticeId = useRef(0);

  const notify = useCallback((text: string, tone: Notice['tone'] = 'info') => {
    noticeId.current += 1;
    setNotice({ id: noticeId.current, text, tone });
  }, []);
  const clearNotice = useCallback(() => setNotice(null), []);

  const refresh = useCallback(async () => {
    const startedAt = mutations.current;
    // The settings read waits in the save queue, so it never reads native state from before
    // a save that was sent earlier but has not committed yet.
    const [settingsResult, statusResult] = await Promise.allSettled([
      enqueue(() => voice.status()),
      onboarding.supported ? onboarding.status() : Promise.resolve(null),
    ]);
    if (settingsResult.status === 'fulfilled') {
      // A newer tap wins over a read that began before it.
      if (mutations.current === startedAt && pending.current === 0) {
        const value = normalize(settingsResult.value);
        confirmed.current = value;
        setSettings(value);
      }
      if (settingsResult.value.openSettings)
        setOpenSettingsRequested((count) => count + 1);
    } else {
      notify('Could not load settings. Reopen Odicto to try again.', 'error');
    }
    if (onboarding.supported) {
      setOnboardingStatus(
        statusResult.status === 'fulfilled' ? statusResult.value : null,
      );
    }
  }, [enqueue, notify]);

  const refreshOnboarding = useCallback(async () => {
    if (!onboarding.supported) return;
    try {
      setOnboardingStatus(await onboarding.status());
    } catch {
      setOnboardingStatus(null);
    }
  }, []);

  // The keyboard picker is a dialog over the app: no visibility change follows a choice.
  useEffect(() => {
    if (!onboarding.supported) return;
    let handle: PluginListenerHandle | undefined;
    let active = true;
    void onboarding
      .onStatusChanged(() => void refreshOnboarding())
      .then((h) => {
        if (active) handle = h;
        else void h.remove();
      })
      .catch(() => undefined);
    return () => {
      active = false;
      void handle?.remove();
    };
  }, [refreshOnboarding]);

  useEffect(() => {
    void refresh();
    const resume = () => {
      if (!document.hidden) void refresh();
    };
    document.addEventListener('visibilitychange', resume);
    return () => document.removeEventListener('visibilitychange', resume);
  }, [refresh]);

  /** Change a setting on screen now; save it in order; undo only on failure. */
  const update = useCallback(
    (
      patch: Partial<VoiceSettings>,
      options: { hapticAfterSave?: boolean } = {},
    ): Promise<boolean> => {
      mutations.current += 1;
      const mine = mutations.current;
      const fields = Object.keys(patch) as (keyof VoiceSettings)[];
      for (const field of fields) fieldOwner.current[field] = mine;
      setSettings((current) => (current ? { ...current, ...patch } : current));
      if (!options.hapticAfterSave) tap();
      pending.current += 1;
      return enqueue(() => voice.save(patch)).then(
        () => {
          pending.current -= 1;
          if (confirmed.current)
            confirmed.current = { ...confirmed.current, ...patch };
          if (options.hapticAfterSave) tap();
          return true;
        },
        (error) => {
          pending.current -= 1;
          const base = confirmed.current;
          if (base) {
            const undo: Partial<VoiceSettings> = {};
            for (const field of fields) {
              if (fieldOwner.current[field] === mine)
                (undo as Record<string, unknown>)[field] = base[field];
            }
            setSettings((current) =>
              current ? { ...current, ...undo } : current,
            );
          }
          notify(errorText(error), 'error');
          return false;
        },
      );
    },
    [enqueue, notify],
  );

  /** Remove a saved key. The key flag clears on screen at once. */
  const removeKey = useCallback(
    (name: KeyName) => {
      mutations.current += 1;
      const mine = mutations.current;
      const flag = KEY_FLAG[name];
      fieldOwner.current[flag] = mine;
      setSettings((current) =>
        current ? { ...current, [flag]: false } : current,
      );
      tap();
      pending.current += 1;
      return enqueue(() => voice.save({ [name]: '' })).then(
        () => {
          pending.current -= 1;
          if (confirmed.current)
            confirmed.current = { ...confirmed.current, [flag]: false };
          return true;
        },
        (error) => {
          pending.current -= 1;
          if (fieldOwner.current[flag] === mine) {
            setSettings((current) =>
              current ? { ...current, [flag]: true } : current,
            );
          }
          notify(errorText(error), 'error');
          return false;
        },
      );
    },
    [enqueue, notify],
  );

  /** Explicit save for text fields and keys. Resolves true when saved. */
  const saveForm = useCallback(
    async (fields: FormValues, keys: KeyValues): Promise<boolean> => {
      mutations.current += 1;
      pending.current += 1;
      setSaving(true);
      try {
        await enqueue(() => voice.save({ ...fields, ...keys }));
        pending.current -= 1;
        const flags: Partial<VoiceSettings> = {};
        for (const name of Object.keys(keys) as KeyName[]) {
          if (keys[name])
            (flags as Record<string, unknown>)[KEY_FLAG[name]] = true;
        }
        confirmed.current = confirmed.current
          ? { ...confirmed.current, ...fields, ...flags }
          : confirmed.current;
        setSettings((current) =>
          current ? { ...current, ...fields, ...flags } : current,
        );
        tap();
        notify('Saved');
        return true;
      } catch (error) {
        pending.current -= 1;
        notify(errorText(error), 'error');
        return false;
      } finally {
        setSaving(false);
      }
    },
    [enqueue, notify],
  );

  return {
    settings,
    onboardingStatus,
    notice,
    notify,
    clearNotice,
    saving,
    refresh,
    refreshOnboarding,
    update,
    removeKey,
    saveForm,
    openSettingsRequested,
  };
}
