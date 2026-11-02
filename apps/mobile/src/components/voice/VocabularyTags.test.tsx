import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { VocabularyTags } from './VocabularyTags';

describe('VocabularyTags accessibility', () => {
  it('labels remove actions individually and preserves readable phrases', () => {
    const html = renderToStaticMarkup(
      <VocabularyTags
        id="hints"
        describedBy="help"
        state={{ tags: ['New York', 'नमस्ते'], input: '', error: '' }}
        disabled={false}
        onInput={() => undefined}
        onCommit={() => undefined}
        onRemove={() => undefined}
        onClear={() => undefined}
      />,
    );
    expect(html).toContain('aria-label="Remove New York"');
    expect(html).toContain('aria-label="Remove नमस्ते"');
    expect(html).toContain('aria-describedby="help"');
    expect(html).toContain('Clear all hints');
    expect(html).toContain('aria-hidden="true">×');
  });

  it('exposes an inline error and retains rejected input', () => {
    const html = renderToStaticMarkup(
      <VocabularyTags
        id="hints"
        state={{
          tags: [],
          input: 'Keep this input',
          error: 'Remove or shorten a term.',
        }}
        disabled={true}
        onInput={() => undefined}
        onCommit={() => undefined}
        onRemove={() => undefined}
        onClear={() => undefined}
      />,
    );
    expect(html).toContain('value="Keep this input"');
    expect(html).toContain('aria-invalid="true"');
    expect(html).toContain('role="status"');
    expect(html).toContain('disabled=""');
    expect(html).toContain('Remove or shorten a term.');
  });
});
