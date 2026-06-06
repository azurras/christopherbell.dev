import assert from 'node:assert/strict';
import test from 'node:test';

import {
  composerPreviewMarkup,
  composerPreviewModel
} from '../../main/resources/static/js/lib/composer-preview.js';

const sanitize = value => String(value ?? '')
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#39;');

test('composerPreviewModel trims empty text and reports remaining characters', () => {
  const model = composerPreviewModel('   ', 280);

  assert.equal(model.hasContent, false);
  assert.equal(model.remaining, 280);
  assert.equal(model.overLimit, false);
});

test('composerPreviewModel detects over-limit drafts', () => {
  const model = composerPreviewModel('x'.repeat(281), 280);

  assert.equal(model.hasContent, true);
  assert.equal(model.remaining, -1);
  assert.equal(model.overLimit, true);
});

test('composerPreviewMarkup links mentions and renders supported rich embeds', () => {
  const markup = composerPreviewMarkup(
    composerPreviewModel('hey @alice https://example.com/cat.jpg', 280),
    sanitize
  );

  assert.match(markup, /class="composer-preview"/);
  assert.match(markup, /href="\/u\/alice"/);
  assert.match(markup, /href="https:\/\/example.com\/cat.jpg"/);
  assert.match(markup, /post-rich-image/);
});

test('composerPreviewMarkup renders a quiet empty state', () => {
  const markup = composerPreviewMarkup(composerPreviewModel('', 280), sanitize);

  assert.match(markup, /composer-preview-empty/);
  assert.doesNotMatch(markup, /post-rich-image/);
});

test('composerPreviewMarkup escapes malicious draft text before innerHTML injection', () => {
  const markup = composerPreviewMarkup(
    composerPreviewModel('<script>alert(1)</script> <img src=x onerror=alert(1)> @<bad'),
    sanitize
  );

  assert.doesNotMatch(markup, /<script>/);
  assert.doesNotMatch(markup, /<img/);
  assert.doesNotMatch(markup, /href="\/u\/&lt;bad"/);
  assert.match(markup, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.match(markup, /&lt;img src=x onerror=alert\(1\)&gt;/);
});

test('composerPreviewMarkup escapes URL attribute breakout attempts', () => {
  const markup = composerPreviewMarkup(
    composerPreviewModel('look https://example.com/"onmouseover="alert(1).jpg'),
    sanitize
  );

  assert.match(markup, /href="https:\/\/example.com\/&quot;onmouseover=&quot;alert"/);
  assert.doesNotMatch(markup, /\sonmouseover=/);
});
