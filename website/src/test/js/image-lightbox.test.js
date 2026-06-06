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
