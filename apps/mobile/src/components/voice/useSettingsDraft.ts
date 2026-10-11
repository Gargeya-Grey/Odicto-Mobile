import { useCallback, useState } from 'react';
import type { VoiceSettings } from '../../native/voice';
import {
  FORM_FIELDS,
  type FormField,
  type FormValues,
  type KeyName,
  type KeyValues,
} from './useVoiceSettings';

const NO_KEYS: Record<KeyName, string> = {
  geminiKey: '',
  groqKey: '',
  openrouterKey: '',
};

/**
 * Unsaved edits for the text settings and key fields. An edit only exists
 * while it differs from the saved value, so "unsaved" is simply "has edits".
 * Key text lives only in these input states until it goes to `voice.save`.
 */
export function useSettingsDraft(
  settings: VoiceSettings | null,
  saveForm: (fields: FormValues, keys: KeyValues) => Promise<boolean>,
) {
  const [edits, setEdits] = useState<Partial<FormValues>>({});
  const [keys, setKeys] = useState<Record<KeyName, string>>(NO_KEYS);

  const value = useCallback(
    <K extends FormField>(field: K): FormValues[K] =>
      (edits[field] ?? settings?.[field]) as FormValues[K],
    [edits, settings],
  );
  const setFields = useCallback((next: Partial<FormValues>) => {
    setEdits((current) => ({ ...current, ...next }));
  }, []);
  const setKey = useCallback((name: KeyName, text: string) => {
    setKeys((current) => ({ ...current, [name]: text }));
  }, []);

  const dirty =
    Boolean(settings) &&
    (FORM_FIELDS.some(
      (field) => field in edits && edits[field] !== settings?.[field],
    ) ||
      Object.values(keys).some((text) => text.trim() !== ''));

  const discard = useCallback(() => {
    setEdits({});
    setKeys(NO_KEYS);
  }, []);

  const save = useCallback(async () => {
    if (!settings) return false;
    const fields = {} as FormValues;
    for (const field of FORM_FIELDS)
      (fields as Record<string, unknown>)[field] = value(field);
    const sent: KeyValues = {};
    const snapshot = { ...keys };
    for (const name of Object.keys(keys) as KeyName[]) {
      const text = keys[name].trim();
      if (text) sent[name] = text;
    }
    const saved = await saveForm(fields, sent);
    if (saved) {
      setEdits((current) => {
        const next = { ...current };
        for (const field of FORM_FIELDS)
          if (next[field] === fields[field]) delete next[field];
        return next;
      });
      setKeys((current) => {
        const next = { ...current };
        for (const name of Object.keys(next) as KeyName[])
          if (next[name] === snapshot[name]) next[name] = '';
        return next;
      });
    }
    return saved;
  }, [keys, saveForm, settings, value]);

  return { value, setFields, keys, setKey, dirty, discard, save };
}
