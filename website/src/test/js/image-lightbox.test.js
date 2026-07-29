import assert from 'node:assert/strict';
import test from 'node:test';

import {
  imageFallbackMarkup,
  imageLightboxMarkup
} from '../../main/resources/static/js/lib/image-lightbox.js';

test('imageLightboxMarkup renders an accessible dialog shell', () => {
  const markup = imageLightboxMarkup('https://example.com/a.jpg');

  assert.match(markup, /role="dialog"/);
  assert.match(markup, /aria-modal="true"/);
  assert.match(markup, /tabindex="-1"/);
  assert.match(markup, /src="https:\/\/example.com\/a.jpg"/);
});

test('imageFallbackMarkup keeps the source link available', () => {
  const markup = imageFallbackMarkup('https://example.com/a.jpg');

  assert.match(markup, /Image unavailable/);
  assert.match(markup, /href="https:\/\/example.com\/a.jpg"/);
});

test('image helpers never activate non-HTTP image values', () => {
  for (const src of [
    'javascript:alert(1)',
    'data:image/png;base64,AAAA',
    '/relative.jpg',
    '//cdn.example.com/protocol-relative.jpg',
    'http://[malformed'
  ]) {
    assert.equal(imageLightboxMarkup(src), '');
    const fallback = imageFallbackMarkup(src);
    assert.match(fallback, /Image unavailable/);
    assert.doesNotMatch(fallback, /<a\b/);
    assert.doesNotMatch(fallback, /href=/);
  }
});
