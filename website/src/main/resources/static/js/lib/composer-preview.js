import { linkMentions } from './util.js';
import { richEmbedMarkupForPost } from './feed-render.js';

/**
 * Build a deterministic preview model for draft post text.
 */
export function composerPreviewModel(text, maxLength = 280) {
  const rawText = String(text ?? '');
  const trimmedText = rawText.trim();
  const length = trimmedText.length;

  return {
    text: rawText,
    trimmedText,
    hasContent: length > 0,
    length,
    maxLength,
    remaining: maxLength - length,
    overLimit: length > maxLength
  };
}

/**
 * Render sanitized preview markup using the same rich embed detector as feed cards.
 */
export function composerPreviewMarkup(model, sanitize) {
  if (typeof sanitize !== 'function') return '';

  if (!model?.hasContent) {
    return '<div class="composer-preview composer-preview-empty">Preview appears as you type.</div>';
  }

  const linkedText = linkMentions(model.text);
  const richEmbeds = richEmbedMarkupForPost({ text: model.text, linkPreviews: [] }, sanitize);
  const counterClass = model.overLimit ? ' composer-preview-count-over' : '';

  return `<section class="composer-preview" aria-label="Post preview">
    <div class="composer-preview-label">Preview</div>
    <p class="composer-preview-text">${linkedText}</p>
    ${richEmbeds}
    <div class="composer-preview-count${counterClass}">${model.remaining} characters remaining</div>
  </section>`;
}
