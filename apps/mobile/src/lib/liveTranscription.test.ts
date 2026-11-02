import { describe, expect, it } from 'vitest';
import {
  liveProviderOptions,
  OPENAI_LIVE_MODELS,
  geminiLiveModelGroups,
  geminiLiveModelLabel,
} from './liveTranscription';

describe('Live provider choices', () => {
  it('offers Gemini transcription by name and filters without requiring its exact ID', () => {
    expect(
      geminiLiveModelGroups('TRANSCRIBE', 'gemini-3.5-transcribe-live').flatMap(
        (group) => group.options,
      ),
    ).toEqual([
      {
        value: 'gemini-3.5-transcribe-live',
        label: 'Gemini 3.5 Transcribe Live',
      },
    ]);
    expect(geminiLiveModelLabel('gemini-3.5-transcribe-live')).toBe(
      'Gemini 3.5 Transcribe Live',
    );
    expect(geminiLiveModelGroups('no such model', '')).toEqual([]);
  });

  it('preserves a saved custom model without offering unverified typed IDs', () => {
    expect(geminiLiveModelGroups('', 'gemini-custom-live')[1]).toEqual({
      label: 'Current model',
      options: [{ value: 'gemini-custom-live', label: 'gemini-custom-live' }],
    });
    expect(geminiLiveModelGroups('gemini-made-up', '')).toEqual([]);
  });
  it('enables OpenAI only after its key is saved', () => {
    expect(liveProviderOptions(false, 'gemini')).toEqual([
      { value: 'gemini', label: 'Gemini' },
    ]);
    expect(liveProviderOptions(true, 'gemini')).toEqual([
      { value: 'gemini', label: 'Gemini' },
      { value: 'openai', label: 'OpenAI', disabled: false },
    ]);
  });

  it('keeps a removed-key selection visible without silently switching providers', () => {
    expect(liveProviderOptions(false, 'openai')[1]).toEqual({
      value: 'openai',
      label: 'OpenAI',
      disabled: true,
    });
  });

  it('offers the supported realtime transcription models', () => {
    expect(OPENAI_LIVE_MODELS.map((model) => model.value)).toEqual([
      'gpt-live-transcribe',
      'gpt-realtime-whisper',
    ]);
  });
});
