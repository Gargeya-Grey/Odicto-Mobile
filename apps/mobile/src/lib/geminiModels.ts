import { MAX_MODEL_ID, type ModelGroup, type ModelOption } from './modelGroups';
import type { GeminiModel } from '../native/voice';

export const DEFAULT_GEMINI_MODEL = 'gemini-3.5-flash-lite';

// Google text-generation models checked 2026-10-31:
// https://ai.google.dev/gemini-api/docs/models
const RECENT: readonly ModelOption[] = [
  { value: 'gemini-4-argon', label: 'Gemini 4 Argon' },
  { value: 'gemini-3.8-flash', label: 'Gemini 3.8 Flash' },
];

const OTHER: readonly ModelOption[] = [
  { value: 'gemini-3.6-flash', label: 'Gemini 3.6 Flash' },
  { value: DEFAULT_GEMINI_MODEL, label: 'Gemini 3.5 Flash-Lite' },
];

const ALL = [...RECENT, ...OTHER];

export function validGeminiModels(
  models: readonly GeminiModel[],
): GeminiModel[] {
  const fetched = new Map<string, GeminiModel>();
  for (const item of models) {
    if (typeof item?.id !== 'string' || typeof item.name !== 'string') continue;
    const id = item.id.trim();
    const key = id.toLowerCase();
    if (
      id.length > MAX_MODEL_ID ||
      !/^[a-z0-9][a-z0-9._-]*$/i.test(id) ||
      fetched.has(key)
    )
      continue;
    fetched.set(key, { id, name: item.name.trim() });
  }
  return [...fetched.values()];
}

function catalog(models: readonly GeminiModel[]) {
  const fetched = new Map<string, ModelOption>();
  for (const item of validGeminiModels(models)) {
    const key = item.id.toLowerCase();
    const bundled = ALL.find((option) => option.value.toLowerCase() === key);
    fetched.set(key, {
      value: bundled?.value ?? item.id,
      label: item.name || bundled?.label || item.id,
    });
  }
  const useGoogleName = (option: ModelOption) =>
    fetched.get(option.value.toLowerCase()) ?? option;
  const recent = RECENT.filter(
    (option) => fetched.size === 0 || fetched.has(option.value.toLowerCase()),
  ).map(useGoogleName);
  const other = OTHER.filter(
    (option) => fetched.size === 0 || fetched.has(option.value.toLowerCase()),
  ).map(useGoogleName);
  const additional = [...fetched.entries()]
    .filter(
      ([key]) => !ALL.some((option) => option.value.toLowerCase() === key),
    )
    .map(([, option]) => option)
    .sort(
      (a, b) =>
        a.label.localeCompare(b.label) || a.value.localeCompare(b.value),
    );
  return { recent, other, additional };
}

export function geminiModelLabel(
  model: string,
  models: readonly GeminiModel[] = [],
): string {
  const id = model || DEFAULT_GEMINI_MODEL;
  const { recent, other, additional } = catalog(models);
  return (
    [...recent, ...other, ...additional].find((item) => item.value === id)
      ?.label ??
    ALL.find((item) => item.value === id)?.label ??
    id
  );
}

export function geminiModelGroups(
  search: string,
  current: string,
  models: readonly GeminiModel[] = [],
): ModelGroup[] {
  const typed = search.trim();
  const query = typed.toLowerCase();
  const matches = (item: ModelOption) =>
    !query ||
    item.label.toLowerCase().includes(query) ||
    item.value.toLowerCase().includes(query);
  const catalogModels = catalog(models);
  const available = [
    ...catalogModels.recent,
    ...catalogModels.other,
    ...catalogModels.additional,
  ];
  const recent = catalogModels.recent.filter(matches);
  const other = catalogModels.other.filter(matches);
  const additional = catalogModels.additional.filter(matches);
  const selected = current || DEFAULT_GEMINI_MODEL;
  const selectedLabel = geminiModelLabel(selected, models);
  const selectedUnknown = !available.some((item) => item.value === selected);
  const currentOption =
    selectedUnknown && matches({ value: selected, label: selectedLabel })
      ? [{ value: selected, label: selectedLabel }]
      : [];
  const canUseTyped =
    typed.length <= MAX_MODEL_ID &&
    /^gemini-[a-z0-9][a-z0-9._-]*$/i.test(typed) &&
    selected.toLowerCase() !== query &&
    !available.some((item) => item.value.toLowerCase() === query);

  return [
    ...(recent.length ? [{ label: 'Recent', options: recent }] : []),
    ...(other.length ? [{ label: 'More models', options: other }] : []),
    ...(additional.length
      ? [{ label: 'From Google', options: additional }]
      : []),
    ...(currentOption.length
      ? [{ label: 'Current model', options: currentOption }]
      : []),
    ...(canUseTyped
      ? [
          {
            label: 'Model ID',
            options: [{ value: typed, label: `Use “${typed}”` }],
          },
        ]
      : []),
  ];
}
