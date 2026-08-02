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
  const css = fs.readFileSync(
    'website/src/main/resources/static/css/command-center.css', 'utf8');

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

test('back office Music permissions expose labelled read and write controls', () => {
  const html = fs.readFileSync('website/src/main/resources/templates/back-office.html', 'utf8');

  assert.match(html, /id="musicPermissionsTemplate"/);
  assert.match(html, /id="musicReadPermission"[^>]+data-music-permission="read"/);
  assert.match(html, /<label[^>]+for="musicReadPermission">Listen to Music<\/label>/);
  assert.match(html, /id="musicWritePermission"[^>]+data-music-permission="write"/);
  assert.match(html, /<label[^>]+for="musicWritePermission">Manage Music<\/label>/);
});

test('Void Explore exposes five independently labelled live regions', () => {
  const html = fs.readFileSync('website/src/main/resources/templates/void/explore.html', 'utf8');

  for (const section of ['new', 'fading', 'revived', 'topics', 'people']) {
    assert.match(html, new RegExp(`data-discovery-section="${section}"`));
  }
  assert.match(html, /aria-labelledby="voidExploreTitle"/);
  assert.match(html, /aria-live="polite"/);
  assert.match(html, /data-discovery-action="retry"/);
  assert.match(html, /data-discovery-action="more"/);
});

test('every button declares its intended type explicitly', () => {
  const files = [
    'website/src/main/resources/templates/post.html',
    'website/src/main/resources/templates/messages.html',
    'website/src/main/resources/templates/music.html',
    'website/src/main/resources/templates/void/index.html',
    'website/src/main/resources/static/js/lib/feed-render.js',
  ];

  for (const file of files) {
    const source = fs.readFileSync(file, 'utf8');
    const ambiguousButtons = [...source.matchAll(/<button\b[^>]*>/gi)]
      .map((match) => match[0])
      .filter((button) => !/\btype=(?:"[^"]+"|'[^']+')/i.test(button));
    assert.deepEqual(ambiguousButtons, [], `${file} has buttons without an explicit type`);
  }

  const music = fs.readFileSync('website/src/main/resources/templates/music.html', 'utf8');
  assert.equal(
    (music.match(/<button\b[^>]*value="cancel"[^>]*formnovalidate[^>]*>/g) || []).length,
    2,
    'dialog Cancel submitters must bypass unrelated required-field validation'
  );
});

test('The Bell archive pages expose one main landmark and one page heading', () => {
  for (const name of ['index', 'tony']) {
    const html = fs.readFileSync(`website/src/main/resources/templates/thebell/${name}.html`, 'utf8');
    assert.equal((html.match(/<main\b/g) || []).length, 1);
    assert.equal((html.match(/<h1\b/g) || []).length, 1);
    for (const link of html.matchAll(/<a\b[^>]*target="_blank"[^>]*>/g)) {
      assert.match(link[0], /rel="noopener noreferrer"/);
    }
  }
});

test('authentication forms provide stable names and autocomplete purposes', () => {
  const expectations = new Map([
    ['login.html', ['name="email" autocomplete="username"', 'name="password" autocomplete="current-password"']],
    ['signup.html', [
      'name="email" autocomplete="email"',
      'name="username" autocomplete="username"',
      'name="firstName" autocomplete="given-name"',
      'name="lastName" autocomplete="family-name"',
      'name="password" autocomplete="new-password"',
    ]],
    ['forgot-password.html', ['name="email" autocomplete="email"']],
    ['reset-password.html', [
      'name="password" autocomplete="new-password"',
      'name="confirmPassword" autocomplete="new-password"',
    ]],
  ]);

  for (const [name, attributes] of expectations) {
    const html = fs.readFileSync(`website/src/main/resources/templates/${name}`, 'utf8');
    assert.match(html, /<form\b[^>]*method="post"/i);
    for (const attribute of attributes) {
      assert.match(html, new RegExp(attribute));
    }
  }
});
