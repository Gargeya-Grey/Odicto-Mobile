import { describe, expect, it } from 'vitest';
import { geminiModelGroups, geminiModelLabel } from './geminiModels';

const options = (search = '', current = '') =>
  geminiModelGroups(search, current).flatMap((group) => group.options);

describe('Gemini model picker', () => {
  it('offers the newest text models and the existing default by name', () => {
    expect(options()).toEqual(
      expect.arrayContaining([
        { value: 'gemini-4-argon', label: 'Gemini 4 Argon' },
        { value: 'gemini-3.8-flash', label: 'Gemini 3.8 Flash' },
        { value: 'gemini-3.5-flash-lite', label: 'Gemini 3.5 Flash-Lite' },
      ]),
    );
    expect(geminiModelLabel('')).toBe('Gemini 3.5 Flash-Lite');
    expect(geminiModelLabel('gemini-4-argon')).toBe('Gemini 4 Argon');
  });

  it('filters by model name or ID without case sensitivity', () => {
    expect(options('ARGON')).toEqual([
      { value: 'gemini-4-argon', label: 'Gemini 4 Argon' },
    ]);
    expect(options('3.8-flash')).toEqual([
      { value: 'gemini-3.8-flash', label: 'Gemini 3.8 Flash' },
    ]);
    expect(options('no such model')).toEqual([]);
  });

  it('keeps a previously saved custom model visible and selectable', () => {
    const current = 'gemini-custom-preview';
    expect(geminiModelLabel(current)).toBe(current);
    expect(options('', current)).toContainEqual({
      value: current,
      label: current,
    });
    expect(options('custom', current)).toContainEqual({
      value: current,
      label: current,
    });
  });

  it('allows a new Gemini model ID without duplicating known or current models', () => {
    expect(options('gemini-9.1-preview')).toContainEqual({
      value: 'gemini-9.1-preview',
      label: 'Use “gemini-9.1-preview”',
    });
    expect(options('gemini-4-argon')).toEqual([
      { value: 'gemini-4-argon', label: 'Gemini 4 Argon' },
    ]);
    expect(options('gemini-custom-preview', 'gemini-custom-preview')).toEqual([
      { value: 'gemini-custom-preview', label: 'gemini-custom-preview' },
    ]);
    expect(options('not a model')).toEqual([]);
    expect(options(`gemini-${'x'.repeat(194)}`)).toEqual([]);
  });

  it('shows Google candidates without adding absent bundled models', () => {
    const google = [
      { id: 'gemini-4-argon', name: 'Gemini 4 Argon from Google' },
      { id: 'gemini-paid-preview', name: 'Gemini Paid Preview' },
      { id: 'gemini-paid-preview', name: 'Duplicate' },
    ];
    const groups = geminiModelGroups('', 'gemini-paid-preview', google);
    const all = groups.flatMap((group) => group.options);
    expect(groups[0]).toEqual({
      label: 'Recent',
      options: [
        { value: 'gemini-4-argon', label: 'Gemini 4 Argon from Google' },
      ],
    });
    expect(all).toContainEqual({
      value: 'gemini-4-argon',
      label: 'Gemini 4 Argon from Google',
    });
    expect(all).toContainEqual({
      value: 'gemini-paid-preview',
      label: 'Gemini Paid Preview',
    });
    expect(
      all.filter((item) => item.value === 'gemini-paid-preview'),
    ).toHaveLength(1);
    expect(all.some((item) => item.value === 'gemini-3.5-flash-lite')).toBe(
      false,
    );
    expect(all.some((item) => item.value === 'gemini-3.8-flash')).toBe(false);
    expect(geminiModelLabel('gemini-paid-preview', google)).toBe(
      'Gemini Paid Preview',
    );
    expect(
      geminiModelGroups('paid', '', google).flatMap((group) => group.options),
    ).toEqual([{ value: 'gemini-paid-preview', label: 'Gemini Paid Preview' }]);
  });

  it('keeps the current bundled or custom model if Google did not return it', () => {
    const google = [{ id: 'gemini-3.8-flash', name: 'Google Flash' }];
    expect(geminiModelGroups('', 'gemini-4-argon', google)).toContainEqual({
      label: 'Current model',
      options: [{ value: 'gemini-4-argon', label: 'Gemini 4 Argon' }],
    });
    expect(geminiModelLabel('gemini-4-argon', google)).toBe('Gemini 4 Argon');
    expect(geminiModelGroups('argon', 'gemini-4-argon', google)).toContainEqual(
      {
        label: 'Current model',
        options: [{ value: 'gemini-4-argon', label: 'Gemini 4 Argon' }],
      },
    );
    expect(geminiModelGroups('', 'gemini-custom', google)).toContainEqual({
      label: 'Current model',
      options: [{ value: 'gemini-custom', label: 'gemini-custom' }],
    });
  });

  it('shows bundled models when Google has not returned usable choices', () => {
    const invalid = [{ id: 'bad model', name: 'Invalid' }];
    const all = geminiModelGroups('', '', invalid).flatMap(
      (group) => group.options,
    );
    expect(all).toContainEqual({
      value: 'gemini-4-argon',
      label: 'Gemini 4 Argon',
    });
    expect(all).toContainEqual({
      value: 'gemini-3.5-flash-lite',
      label: 'Gemini 3.5 Flash-Lite',
    });
  });

  it('ignores malformed Google entries and keeps a saved custom model', () => {
    const google = [
      { id: '', name: 'Empty' },
      { id: 'model with spaces', name: 'Invalid' },
      { id: 'gemini-no-name', name: '' },
    ];
    const all = geminiModelGroups('', 'custom-saved', google).flatMap(
      (group) => group.options,
    );
    expect(all).toContainEqual({
      value: 'custom-saved',
      label: 'custom-saved',
    });
    expect(all).toContainEqual({
      value: 'gemini-no-name',
      label: 'gemini-no-name',
    });
    expect(
      all.some((item) => item.label === 'Empty' || item.label === 'Invalid'),
    ).toBe(false);
  });
});
