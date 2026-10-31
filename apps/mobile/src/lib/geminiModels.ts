import { MAX_MODEL_ID, type ModelGroup, type ModelOption } from './modelGroups';
import type { GeminiModel } from '../native/voice';

export const DEFAULT_GEMINI_MODEL = 'gemini-3.5-flash-lite';

// Google text-generation models checked 2026-10-31:
// https://ai.google.dev/gemini-api/docs/models
const BUNDLED: readonly ModelOption[] = [
  { value: 'gemini-4-argon', label: 'Gemini 4 Argon' },
  { value: 'gemini-3.8-flash', label: 'Gemini 3.8 Flash' },
  { value: 'gemini-3.6-flash', label: 'Gemini 3.6 Flash' },
  { value: DEFAULT_GEMINI_MODEL, label: 'Gemini 3.5 Flash-Lite' },
];

const naturalIds = new Intl.Collator('en', {
  numeric: true,
  sensitivity: 'base',
});

function versionParts(id: string): string[] | null {
  const match = /^gemini-(\d+(?:\.\d+)*)(?=$|[-_])/i.exec(id);
  return match
    ? match[1].split('.').map((part) => part.replace(/^0+(?=\d)/, ''))
    : null;
}

function compareModels(left: ModelOption, right: ModelOption): number {
  const a = versionParts(left.value);
  const b = versionParts(right.value);
  if (a && !b) return -1;
  if (!a && b) return 1;
  if (a && b) {
    for (let i = 0; i < Math.max(a.length, b.length); i += 1) {
      const x = a[i] ?? '0';
      const y = b[i] ?? '0';
      if (x.length !== y.length) return y.length - x.length;
      if (x !== y) return x < y ? 1 : -1;
    }
  }
  const preview = (id: string) =>
    /(?:^|[-_.])(?:preview|exp|experimental)(?:[-_.]|$)/i.test(id);
  const previewOrder =
    Number(preview(left.value)) - Number(preview(right.value));
  if (previewOrder) return previewOrder;
  const idOrder = naturalIds.compare(right.value, left.value);
  if (idOrder) return idOrder;
  return left.value < right.value ? -1 : left.value > right.value ? 1 : 0;
}

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
  const fetched = validGeminiModels(models);
  const options = fetched.length
    ? fetched.map((item) => {
        const bundled = BUNDLED.find(
          (option) => option.value.toLowerCase() === item.id.toLowerCase(),
        );
        return {
          value: bundled?.value ?? item.id,
          label: item.name || bundled?.label || item.id,
        };
      })
    : [...BUNDLED];
  return { options: options.sort(compareModels), live: fetched.length > 0 };
}

export function geminiModelLabel(
  model: string,
  models: readonly GeminiModel[] = [],
): string {
  const id = model || DEFAULT_GEMINI_MODEL;
  const { options } = catalog(models);
  return (
    options.find((item) => item.value === id)?.label ??
    BUNDLED.find((item) => item.value === id)?.label ??
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
  const { options: available, live } = catalog(models);
  const matched = available.filter(matches);
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
    ...(matched.length
      ? [{ label: live ? 'From Google' : 'Included models', options: matched }]
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
