import assert from 'node:assert/strict';
import test from 'node:test';

import {
  expiresSoon,
  formatLifespanCountdown,
  linkPreviewCardMarkup,
  remainingLifespanMs
} from '../../main/resources/static/js/lib/feed-render.js';

test('formatLifespanCountdown shows days and a tabular clock', () => {
  const now = Date.UTC(2026, 4, 21, 12, 0, 0);
  const expiresOn = new Date(now + 90061000).toISOString();

  assert.equal(formatLifespanCountdown(expiresOn, now), '1d 01:01:01');
});

test('formatLifespanCountdown shows the final second before expiry', () => {
  const now = Date.UTC(2026, 4, 21, 12, 0, 0);
  const expiresOn = new Date(now + 1000).toISOString();

  assert.equal(formatLifespanCountdown(expiresOn, now), '00:00:01');
});

test('remainingLifespanMs returns zero once a post has expired', () => {
  const now = Date.UTC(2026, 4, 21, 12, 0, 0);
  const expiresOn = new Date(now - 1).toISOString();

  assert.equal(remainingLifespanMs(expiresOn, now), 0);
});

test('lifespan helpers hide countdowns without a usable expiry', () => {
  assert.equal(remainingLifespanMs('not-a-date'), null);
  assert.equal(formatLifespanCountdown(), '');
});

test('expiresSoon starts at twelve hours remaining', () => {
  const now = Date.UTC(2026, 4, 21, 12, 0, 0);

  assert.equal(expiresSoon({ expiresOn: new Date(now + 12 * 60 * 60 * 1000).toISOString() }, now), true);
  assert.equal(expiresSoon({ expiresOn: new Date(now + 12 * 60 * 60 * 1000 + 1).toISOString() }, now), false);
});

test('link preview markup keeps rich metadata outside post text', () => {
  const markup = linkPreviewCardMarkup({
    url: 'https://example.com/lunch',
    domain: 'example.com',
    title: '<Lunch Picks>',
    description: 'Three places nearby',
    imageUrl: 'https://example.com/lunch.jpg'
  }, text => String(text).replaceAll('<', '&lt;').replaceAll('>', '&gt;'));

  assert.match(markup, /class="post-link-preview"/);
  assert.match(markup, /&lt;Lunch Picks&gt;/);
  assert.match(markup, /Three places nearby/);
  assert.match(markup, /lunch\.jpg/);
});
