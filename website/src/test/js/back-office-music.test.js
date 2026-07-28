import assert from 'node:assert/strict';
import test from 'node:test';

const { musicAccessAttemptMarkup } = await import(
  '../../main/resources/static/js/lib/back-office-music.js');

test('denied Music access markup escapes account and IP values', () => {
  const markup = musicAccessAttemptMarkup([{
    principalType: 'IP',
    principal: '<img src=x onerror=alert(1)>',
    reason: '<script>alert(2)</script>',
    count: 3,
    lastAttemptAt: '2026-07-28T12:00:00Z',
  }]);

  assert.match(markup, /3 attempt\(s\)/);
  assert.match(markup, /&lt;img src=x onerror=alert\(1\)&gt;/);
  assert.match(markup, /&lt;script&gt;alert\(2\)&lt;\/script&gt;/);
  assert.doesNotMatch(markup, /<img|<script>/);
});

test('denied Music access markup has a useful empty state', () => {
  assert.match(musicAccessAttemptMarkup([]), /No denied Music access attempts/);
});
