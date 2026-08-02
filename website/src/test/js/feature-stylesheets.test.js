import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const RESOURCES = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../main/resources',
);

async function resource(relativePath) {
  return readFile(path.join(RESOURCES, relativePath), 'utf8').catch(() => '');
}

test('feature-only selectors have one dedicated stylesheet owner', async () => {
  const [main, voidDiscovery, commandCenter, sharedFolder, siteMediaPlayer] = await Promise.all([
    resource('static/css/main.css'),
    resource('static/css/void-discovery.css'),
    resource('static/css/command-center.css'),
    resource('static/css/shared-folder.css'),
    resource('static/css/site-media-player.css'),
  ]);
  const ownership = [
    ['.void-discovery-hero', voidDiscovery],
    ['.command-center-page', commandCenter],
    ['.shared-folder-main', sharedFolder],
    ['.site-media-player-host', siteMediaPlayer],
  ];

  for (const [selector, owner] of ownership) {
    assert.doesNotMatch(main, new RegExp(selector.replace('.', '\\.')));
    assert.match(owner, new RegExp(selector.replace('.', '\\.')));
  }
});

test('feature templates link their versioned dedicated stylesheets', async () => {
  const templates = [
    ['templates/command-center.html', 'command-center.css'],
    ['templates/shared-folder.html', 'shared-folder.css'],
    ['templates/void/explore.html', 'void-discovery.css'],
    ['templates/void/topic.html', 'void-discovery.css'],
  ];

  for (const [template, stylesheet] of templates) {
    const source = await resource(template);
    assert.match(source, new RegExp(
      `href="/css/${stylesheet.replace('.', '\\.')}"[^>]+th:href="@\\{/css/${stylesheet.replace('.', '\\.')}\\}"`,
    ));
  }
});
