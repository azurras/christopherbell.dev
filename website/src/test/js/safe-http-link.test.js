import assert from 'node:assert/strict';
import test from 'node:test';

import { appendSafeHttpLink, safeHttpUrl } from '../../main/resources/static/js/lib/safe-http-link.js';

test('safe HTTP URL accepts trimmed mixed-case HTTP schemes', () => {
  assert.equal(safeHttpUrl('  HtTpS://Example.com/menu  '), 'https://example.com/menu');
  assert.equal(safeHttpUrl('http://example.com'), 'http://example.com/');
});

test('safe HTTP URL rejects active, credentialed, relative, and protocol-relative URLs', () => {
  for (const value of [
    'javascript:alert(1)',
    'data:text/html,hello',
    'https://user:secret@example.com',
    '/menu',
    '//example.com/menu',
  ]) {
    assert.equal(safeHttpUrl(value), null);
  }
});

test('link construction uses DOM properties instead of HTML interpolation', () => {
  const appended = [];
  const document = {
    createElement: () => ({}),
  };
  const container = {
    ownerDocument: document,
    append: (value) => appended.push(value),
  };
  const link = appendSafeHttpLink(container, 'https://example.com/menu', {
    className: 'website',
    label: 'Menu',
  });

  assert.equal(appended[0], link);
  assert.equal(link.href, 'https://example.com/menu');
  assert.equal(link.rel, 'noopener noreferrer');
  assert.equal(link.textContent, 'Menu');
});
