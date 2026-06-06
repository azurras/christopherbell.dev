import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

test('main stylesheet includes visible focus states for Void controls', () => {
  const css = fs.readFileSync('website/src/main/resources/static/css/main.css', 'utf8');

  assert.match(css, /:focus-visible/);
  assert.match(css, /\.post-action:focus-visible/);
  assert.match(css, /\.void-thread-control:focus-visible/);
  assert.match(css, /\.post-rich-image-trigger:hover,\n\.post-rich-image-trigger:focus-visible/);
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
