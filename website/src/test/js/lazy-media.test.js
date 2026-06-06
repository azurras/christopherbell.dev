import assert from 'node:assert/strict';
import test from 'node:test';

import { lazyIframeMarkup } from '../../main/resources/static/js/lib/lazy-media.js';

test('lazyIframeMarkup stores iframe source in data-src before activation', () => {
  const markup = lazyIframeMarkup({
    className: 'post-rich-iframe',
    src: 'https://example.com/embed',
    title: 'Example embed',
    allow: 'fullscreen'
  }, value => String(value));

  assert.match(markup, /data-src="https:\/\/example.com\/embed"/);
  assert.doesNotMatch(markup, /\ssrc="https:\/\/example.com\/embed"/);
});
