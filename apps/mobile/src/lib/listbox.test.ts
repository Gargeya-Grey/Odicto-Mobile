import { describe, expect, it } from 'vitest';
import {
  clampIndex,
  edgeIndex,
  indexOfActiveValue,
  nextTypeaheadBuffer,
  stepIndex,
  typeaheadMatch,
} from './listbox';

describe('indexOfActiveValue', () => {
  it('keeps the active option through catalog insertions and reordering', () => {
    const before = [{ value: 'alpha' }, { value: 'beta' }];
    const after = [{ value: 'new' }, { value: 'beta' }, { value: 'alpha' }];
    expect(before[indexOfActiveValue(before, 'beta')].value).toBe('beta');
    expect(after[indexOfActiveValue(after, 'beta')].value).toBe('beta');
  });
  it('has no active option when it is removed or disabled', () => {
    expect(indexOfActiveValue([{ value: 'other' }], 'beta')).toBe(-1);
    expect(
      indexOfActiveValue([{ value: 'beta', disabled: true }], 'beta'),
    ).toBe(-1);
    expect(indexOfActiveValue([{ value: 'beta' }], null)).toBe(-1);
  });
});

describe('clampIndex', () => {
  it('keeps an index inside the list', () => {
    expect(clampIndex(-3, 4)).toBe(0);
    expect(clampIndex(2, 4)).toBe(2);
    expect(clampIndex(9, 4)).toBe(3);
    expect(clampIndex(1.8, 4)).toBe(1);
  });
  it('has no index for an empty list', () => {
    expect(clampIndex(0, 0)).toBe(-1);
  });
  it('treats NaN as the first entry', () => {
    expect(clampIndex(Number.NaN, 3)).toBe(0);
  });
});

describe('stepIndex', () => {
  it('stops at the ends', () => {
    expect(stepIndex(0, -1, 3)).toBe(0);
    expect(stepIndex(2, 1, 3)).toBe(2);
    expect(stepIndex(0, 1, 3)).toBe(1);
  });
  it('starts at an end when nothing is active', () => {
    expect(stepIndex(-1, 1, 3)).toBe(0);
    expect(stepIndex(-1, -1, 3)).toBe(2);
  });
  it('skips disabled entries', () => {
    const enabled = (i: number) => i !== 1;
    expect(stepIndex(0, 1, 3, enabled)).toBe(2);
    expect(stepIndex(2, -1, 3, enabled)).toBe(0);
    expect(stepIndex(0, 1, 2, enabled)).toBe(0);
  });
  it('has no index for an empty list', () => {
    expect(stepIndex(-1, 1, 0)).toBe(-1);
  });
});

describe('edgeIndex', () => {
  it('finds the first and last enabled entry', () => {
    const enabled = (i: number) => i !== 0 && i !== 3;
    expect(edgeIndex('first', 4, enabled)).toBe(1);
    expect(edgeIndex('last', 4, enabled)).toBe(2);
    expect(edgeIndex('first', 0)).toBe(-1);
  });
});

describe('typeaheadMatch', () => {
  const labels = ['Alpha', 'Apple', 'Banana', 'Berry', 'Cherry'];
  it('finds the first label that starts with the text', () => {
    expect(typeaheadMatch(labels, 'b', 0)).toBe(2);
    expect(typeaheadMatch(labels, 'ber', 0)).toBe(3);
    expect(typeaheadMatch(labels, 'CH', 0)).toBe(4);
  });
  it('wraps around the list', () => {
    expect(typeaheadMatch(labels, 'al', 3)).toBe(0);
  });
  it('keeps the current entry when longer text still matches', () => {
    expect(typeaheadMatch(labels, 'ap', 1)).toBe(1);
  });
  it('moves one letter to the next match, even from a match', () => {
    expect(typeaheadMatch(labels, 'b', 2)).toBe(3);
    expect(typeaheadMatch(labels, 'b', 3)).toBe(2);
    expect(typeaheadMatch(labels, 'a', 0)).toBe(1);
    expect(typeaheadMatch(labels, 'b', -1)).toBe(2);
  });
  it('stays on the only match for one letter', () => {
    expect(typeaheadMatch(labels, 'c', 4)).toBe(4);
  });
  it('cycles on a repeated letter', () => {
    expect(typeaheadMatch(labels, 'bb', 2)).toBe(3);
    expect(typeaheadMatch(labels, 'bbb', 3)).toBe(2);
  });
  it('skips disabled entries and reports no match', () => {
    expect(typeaheadMatch(labels, 'b', 0, (i) => i !== 2)).toBe(3);
    expect(typeaheadMatch(labels, 'z', 0)).toBe(-1);
    expect(typeaheadMatch(labels, '', 0)).toBe(-1);
    expect(typeaheadMatch([], 'a', 0)).toBe(-1);
  });
});

describe('nextTypeaheadBuffer', () => {
  it('joins quick keys and drops old text', () => {
    expect(nextTypeaheadBuffer('b', 'e', 1100, 1000)).toBe('be');
    expect(nextTypeaheadBuffer('be', 'r', 2000, 1000)).toBe('r');
  });
});
