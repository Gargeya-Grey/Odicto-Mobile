import { describe, expect, it } from 'vitest';
import {
  APPEARANCE_STORAGE_KEY,
  nextAppearanceFromTheme,
  parseAppearance,
  readStoredAppearance,
  resolveTheme,
  storeAppearance,
  THEME_COLORS,
} from './theme';

const memory = (initial?: string) => {
  const data = new Map<string, string>();
  if (initial !== undefined) data.set(APPEARANCE_STORAGE_KEY, initial);
  return {
    data,
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
  };
};

const broken = {
  getItem: () => {
    throw new Error('blocked');
  },
  setItem: () => {
    throw new Error('quota');
  },
};

describe('resolveTheme', () => {
  it('follows the phone for system', () => {
    expect(resolveTheme('system', true)).toBe('dark');
    expect(resolveTheme('system', false)).toBe('light');
  });

  it('ignores the phone for an explicit choice', () => {
    expect(resolveTheme('light', true)).toBe('light');
    expect(resolveTheme('light', false)).toBe('light');
    expect(resolveTheme('dark', true)).toBe('dark');
    expect(resolveTheme('dark', false)).toBe('dark');
  });
});

describe('nextAppearanceFromTheme', () => {
  it('always picks the mode that is not showing', () => {
    expect(nextAppearanceFromTheme('dark')).toBe('light');
    expect(nextAppearanceFromTheme('light')).toBe('dark');
  });
});

describe('parseAppearance', () => {
  it('keeps the three known values', () => {
    expect(parseAppearance('system')).toBe('system');
    expect(parseAppearance('light')).toBe('light');
    expect(parseAppearance('dark')).toBe('dark');
  });

  it('turns anything else into system', () => {
    expect(parseAppearance(null)).toBe('system');
    expect(parseAppearance(undefined)).toBe('system');
    expect(parseAppearance('')).toBe('system');
    expect(parseAppearance('DARK')).toBe('system');
    expect(parseAppearance('sepia')).toBe('system');
    expect(parseAppearance(1)).toBe('system');
    expect(parseAppearance({})).toBe('system');
  });
});

describe('THEME_COLORS', () => {
  it('has one hex colour per theme', () => {
    expect(Object.keys(THEME_COLORS).sort()).toEqual(['dark', 'light']);
    expect(THEME_COLORS.dark).toBe('#17191c');
    expect(THEME_COLORS.light).toBe('#f3f6f2');
  });
});

describe('stored appearance', () => {
  it('defaults to system when nothing is saved', () => {
    expect(readStoredAppearance(memory())).toBe('system');
    expect(readStoredAppearance(undefined)).toBe('system');
  });

  it('round-trips under the shared key', () => {
    const storage = memory();
    expect(storeAppearance('light', storage)).toBe(true);
    expect(storage.data.get('odicto-appearance')).toBe('light');
    expect(readStoredAppearance(storage)).toBe('light');
  });

  it('ignores a value it does not know', () => {
    expect(readStoredAppearance(memory('sepia'))).toBe('system');
  });

  it('survives a storage that throws', () => {
    expect(readStoredAppearance(broken)).toBe('system');
    expect(storeAppearance('dark', broken)).toBe(false);
  });
});
