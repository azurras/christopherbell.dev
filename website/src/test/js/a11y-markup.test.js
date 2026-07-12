import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

test('main stylesheet includes visible focus states for Void controls', () => {
  const css = fs.readFileSync('website/src/main/resources/static/css/main.css', 'utf8');

  assert.match(css, /:focus-visible/);
  assert.match(css, /\.post-action:focus-visible/);
  assert.match(css, /\.void-thread-control:focus-visible/);
  assert.match(css, /\.post-rich-image-trigger:hover,\r?\n\.post-rich-image-trigger:focus-visible/);
});

test('post template exposes thread controls as a labelled group', () => {
  const html = fs.readFileSync('website/src/main/resources/templates/post.html', 'utf8');

  assert.match(html, /role="group" aria-label="Thread controls"/);
});

test('image lightbox keeps keyboard behavior in its module', () => {
  const script = fs.readFileSync('website/src/main/resources/static/js/lib/image-lightbox.js', 'utf8');

  assert.match(script, /keyEvent\.key === 'Escape'/);
  assert.match(script, /keyEvent\.key !== 'Tab'/);
});

test('command center shell exposes labelled regions and an accessible action dialog', () => {
  const html = fs.readFileSync('website/src/main/resources/templates/command-center.html', 'utf8');
  const css = fs.readFileSync('website/src/main/resources/static/css/main.css', 'utf8');

  assert.match(html, /id="commandCenterRoot"[^>]*class="[^"]*d-none/);
  assert.match(html, /aria-labelledby="commandCenterTitle"/);
  assert.match(html, /aria-live="polite"/);
  assert.match(html, /aria-live="assertive"/);
  assert.match(html, /<dialog[^>]+id="commandActionDialog"/);
  assert.match(html, /<label[^>]+for="commandActionPassword"/);
  assert.match(html, /<label[^>]+for="commandActionPhrase"/);
  assert.match(html, /id="commandDialogStatus"[^>]+aria-live="assertive"/);
  assert.match(html, /aria-label="Server log level"/);
  assert.match(html, /aria-label="Search server logs literally"/);
  assert.match(html, /id="commandDangerZone"/);
  assert.match(html, /<div class="command-center-health">\s*<div[^>]+aria-live="polite"[^>]*>\s*<span id="commandHealthBadge"[\s\S]*?<\/div>\s*<span id="commandSampleAge"/);
  assert.match(css, /\.command-center-page button:focus-visible/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
});

test('back office links admins to the dedicated command center', () => {
  const html = fs.readFileSync('website/src/main/resources/templates/back-office.html', 'utf8');

  assert.match(html, /href="\/command-center"/);
});
