import { useCallback, useRef, useState } from 'react';
import type { VoiceSettings } from '../../native/voice';
import {
  commitVocabulary,
  editVocabulary,
  sameVocabulary,
  vocabularyDraft,
  type VocabularyDraft,
} from '../../lib/vocabularyHints';
import {
  switchPolishProvider,
  type PolishModels,
  type PolishProvider,
} from '../../lib/polishProviderDraft';
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
  openaiKey: '',
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
  const [vocabulary, setVocabulary] = useState<VocabularyDraft | null>(null);
  const [saving, setSaving] = useState(false);
  const vocabularyState =
    vocabulary ?? vocabularyDraft(settings?.vocabularyHints ?? []);
  const polishModels = useRef<PolishModels>({});

  const value = useCallback(
    <K extends FormField>(field: K): FormValues[K] =>
      (field === 'vocabularyHints'
        ? vocabularyState.tags
        : (edits[field] ?? settings?.[field])) as FormValues[K],
    [edits, settings, vocabularyState.tags],
  );
  const setFields = useCallback((next: Partial<FormValues>) => {
    if (next.vocabularyHints)
      setVocabulary(vocabularyDraft(next.vocabularyHints));
    setEdits((current) => ({ ...current, ...next }));
  }, []);
  const setKey = useCallback((name: KeyName, text: string) => {
    setKeys((current) => ({ ...current, [name]: text }));
  }, []);
  const resetPolishModels = useCallback(() => {
    polishModels.current = {};
  }, []);
  const setPolishProvider = useCallback(
    (next: PolishProvider) => {
      const current = value('polishProvider') ?? 'openrouter';
      const currentModel = value('polishModel') ?? 'poolside/laguna-xs-2.1';
      const result = switchPolishProvider(
        polishModels.current,
        current,
        currentModel,
        next,
        settings
          ? {
              provider: settings.polishProvider ?? 'openrouter',
              model: settings.polishModel,
            }
          : undefined,
      );
      polishModels.current = result.remembered;
      setFields({ polishProvider: next, polishModel: result.model });
    },
    [setFields, settings, value],
  );

  const dirty =
    Boolean(settings) &&
    (FORM_FIELDS.some(
      (field) =>
        field !== 'vocabularyHints' &&
        field in edits &&
        edits[field] !== settings?.[field],
    ) ||
      !sameVocabulary(vocabularyState.tags, settings?.vocabularyHints ?? []) ||
      vocabularyState.input.trim() !== '' ||
      Object.values(keys).some((text) => text.trim() !== ''));

  const discard = useCallback(() => {
    setEdits({});
    setKeys(NO_KEYS);
    setVocabulary(null);
    resetPolishModels();
  }, [resetPolishModels]);

  const save = useCallback(async () => {
    if (!settings) return false;
    const preparedVocabulary = commitVocabulary(vocabularyState);
    if (preparedVocabulary.error) {
      setVocabulary(preparedVocabulary);
      return false;
    }
    const fields = {} as FormValues;
    for (const field of FORM_FIELDS)
      (fields as Record<string, unknown>)[field] = value(field);
    fields.vocabularyHints = preparedVocabulary.tags;
    const sent: KeyValues = {};
    const snapshot = { ...keys };
    for (const name of Object.keys(keys) as KeyName[]) {
      const text = keys[name].trim();
      if (text) sent[name] = text;
    }
    setSaving(true);
    const saved = await saveForm(fields, sent).finally(() => setSaving(false));
    if (saved) {
      setVocabulary(null);
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
  }, [keys, saveForm, settings, value, vocabularyState]);

  const changeVocabulary = (input: string, composing = false) =>
    setVocabulary((current) =>
      editVocabulary(
        current ?? vocabularyDraft(settings?.vocabularyHints ?? []),
        input,
        composing,
      ),
    );
  const addVocabulary = (text?: string) =>
    setVocabulary((current) =>
      commitVocabulary(
        current ?? vocabularyDraft(settings?.vocabularyHints ?? []),
        text,
      ),
    );
  const removeVocabulary = (index: number) =>
    setVocabulary((current) => {
      const state = current ?? vocabularyDraft(settings?.vocabularyHints ?? []);
      return editVocabulary(
        { ...state, tags: state.tags.filter((_, i) => i !== index) },
        state.input,
      );
    });

  return {
    value,
    setFields,
    setPolishProvider,
    resetPolishModels,
    keys,
    setKey,
    dirty,
    discard,
    save,
    vocabulary: vocabularyState,
    changeVocabulary,
    addVocabulary,
    removeVocabulary,
    clearVocabulary: () => setVocabulary(vocabularyDraft([])),
    saving,
  };
}
