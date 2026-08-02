import assert from 'node:assert/strict';
import test from 'node:test';

import * as util from '../../main/resources/static/js/lib/util.js';

test('linkMentions links mentions and web urls without swallowing sentence punctuation', () => {
  const html = util.linkMentions('Ask @bell or open https://example.com/lunch.');

  assert.match(html, /href="\/u\/bell" class="mention-link">@bell<\/a>/);
  assert.match(html, /href="https:\/\/example\.com\/lunch" class="text-link" target="_blank" rel="noopener noreferrer">https:\/\/example\.com\/lunch<\/a>\./);
});

test('URL punctuation trimming removes every trailing sentence delimiter', () => {
  assert.equal(typeof util.trimUrlPunctuation, 'function');
  assert.equal(util.trimUrlPunctuation('https://example.com/path...!?'),
    'https://example.com/path');
  assert.equal(util.trimUrlPunctuation(null), '');
});
