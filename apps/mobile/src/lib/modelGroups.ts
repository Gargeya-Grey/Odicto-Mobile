import type { OpenRouterModel } from '../native/voice';

export type ModelOption = { value: string; label: string };
export type ModelGroup = { label?: string; options: ModelOption[] };

/** Longest model ID a person can type in; the settings store refuses longer ones. */
export const MAX_MODEL_ID = 200;

export const modelLabel = (item: OpenRouterModel) =>
  `${item.name}${item.free ? ' · free' : ''}`;

/**
 * Menu groups for an OpenRouter model picker: matches for `search`, sorted by name
 * and grouped by first letter.
 *
 * - `pinned` goes first while it matches the search, and its ID leaves the list.
 * - `allowCustom` offers the typed text as a model ID (`vendor/model`) when no listed
 *   ID equals it, ignoring case, so a model that is missing from the catalog can still be chosen.
 *   It goes last, so a plain search never picks it by default.
 */
export function modelGroups(
  models: readonly OpenRouterModel[],
  search: string,
  options: { pinned?: ModelOption; allowCustom?: boolean } = {},
): ModelGroup[] {
  const { pinned, allowCustom = false } = options;
  const typed = search.trim();
  const query = typed.toLowerCase();
  const matches = models
    .filter(
      (item) =>
        item.id !== pinned?.value &&
        !item.name.toLowerCase().includes('(batch)') &&
        (!query ||
          item.name.toLowerCase().includes(query) ||
          item.id.toLowerCase().includes(query)),
    )
    .sort(
      (a, b) =>
        a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }) ||
        a.id.localeCompare(b.id),
    );
  const byLetter = new Map<string, ModelOption[]>();
  for (const item of matches) {
    const letter = item.name.charAt(0).toUpperCase() || '#';
    const group = byLetter.get(letter) ?? [];
    group.push({ value: item.id, label: modelLabel(item) });
    byLetter.set(letter, group);
  }
  const custom =
    allowCustom &&
    typed !== '' &&
    typed.length <= MAX_MODEL_ID &&
    !/\s/.test(typed) &&
    typed.includes('/') &&
    query !== pinned?.value.toLowerCase() &&
    !models.some((item) => item.id.toLowerCase() === query);
  return [
    ...(pinned && (!query || pinned.label.toLowerCase().includes(query))
      ? [{ options: [pinned] }]
      : []),
    ...[...byLetter.entries()].map(([label, group]) => ({
      label,
      options: group,
    })),
    ...(custom
      ? [
          {
            label: 'Model ID',
            options: [{ value: typed, label: `Use “${typed}”` }],
          },
        ]
      : []),
  ];
}
