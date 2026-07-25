import assert from 'node:assert/strict';
import test from 'node:test';

import * as siteMedia from '../../main/resources/static/js/lib/site-media-player.js';

function anchor(href, { target = '', download = false } = {}) {
  return {
    href,
    target,
    hasAttribute(name) { return name === 'download' && download; },
  };
}

function primaryClick(overrides = {}) {
  return {
    defaultPrevented: false,
    button: 0,
    metaKey: false,
    ctrlKey: false,
    shiftKey: false,
    altKey: false,
    ...overrides,
  };
}

test('persistent navigation accepts ordinary same-site links and rejects browser-owned links', () => {
  assert.equal(typeof siteMedia.persistentNavigationTarget, 'function');
  const current = 'https://www.christopherbell.dev/shared?path=music';

  assert.equal(siteMedia.persistentNavigationTarget(
    anchor('/void'), primaryClick(), current,
  ), 'https://www.christopherbell.dev/void');
  assert.equal(siteMedia.persistentNavigationTarget(
    anchor('https://example.com'), primaryClick(), current,
  ), null);
  assert.equal(siteMedia.persistentNavigationTarget(
    anchor('/download.zip', { download: true }), primaryClick(), current,
  ), null);
  assert.equal(siteMedia.persistentNavigationTarget(
    anchor('/messages', { target: '_blank' }), primaryClick(), current,
  ), null);
  assert.equal(siteMedia.persistentNavigationTarget(
    anchor('/messages'), primaryClick({ ctrlKey: true }), current,
  ), null);
  assert.equal(siteMedia.persistentNavigationTarget(
    anchor('#files'), primaryClick(), current,
  ), null);
});

test('persistent navigator reuses one content frame and mirrors frame history', () => {
  assert.equal(typeof siteMedia.createPersistentSiteNavigator, 'function');
  let current = 'https://www.christopherbell.dev/shared?path=music';
  let created = 0;
  let onLoad;
  const navigated = [];
  const pushed = [];
  const titles = [];
  const navigator = siteMedia.createPersistentSiteNavigator({
    currentHref: () => current,
    createFrame: loadHandler => {
      created += 1;
      onLoad = loadHandler;
      return { navigate: href => navigated.push(href) };
    },
    showFrame: () => {},
    pushHref: href => {
      current = href;
      pushed.push(href);
    },
    setTitle: title => titles.push(title),
  });

  navigator.open('https://www.christopherbell.dev/void');
  onLoad({ href: 'https://www.christopherbell.dev/void', title: 'The Void' });
  navigator.open('https://www.christopherbell.dev/messages');
  onLoad({ href: 'https://www.christopherbell.dev/messages', title: 'Messages' });
  navigator.restore('https://www.christopherbell.dev/void');
  onLoad({ href: 'https://www.christopherbell.dev/void', title: 'The Void' });

  assert.equal(created, 1);
  assert.deepEqual(navigated, [
    'https://www.christopherbell.dev/void',
    'https://www.christopherbell.dev/messages',
    'https://www.christopherbell.dev/void',
  ]);
  assert.deepEqual(pushed, [
    'https://www.christopherbell.dev/void',
    'https://www.christopherbell.dev/messages',
  ]);
  assert.deepEqual(titles, ['The Void', 'Messages', 'The Void']);
});

test('site media session has one owner and fully stops replaced playback', () => {
  assert.equal(typeof siteMedia.createSiteMediaSession, 'function');
  const mounted = [];
  let unmounted = 0;
  const session = siteMedia.createSiteMediaSession({
    mount: playback => mounted.push(playback),
    unmount: () => { unmounted += 1; },
  });
  const first = fakeMedia();
  const second = fakeMedia();

  const firstPlayback = session.start({ kind: 'AUDIO', title: 'First.flac' }, first);
  const secondPlayback = session.start({ kind: 'VIDEO', title: 'Second.mkv' }, second);

  assert.equal(firstPlayback.signal.aborted, true);
  assert.equal(first.pauseCalls, 1);
  assert.equal(first.removedSource, true);
  assert.equal(first.loadCalls, 1);
  assert.equal(second.pauseCalls, 0);
  assert.equal(session.snapshot().media, second);
  assert.equal(session.snapshot().descriptor.title, 'Second.mkv');
  assert.equal(mounted.length, 2);

  secondPlayback.stop();
  assert.equal(second.pauseCalls, 1);
  assert.equal(second.removedSource, true);
  assert.equal(unmounted, 1);
  assert.equal(session.snapshot(), null);
});

test('framed pages delegate playback and navigation to the top document owner', async () => {
  const calls = [];
  const host = {
    playSharedFolder: entry => {
      calls.push(['play', entry.path]);
      return Promise.resolve('playing');
    },
    navigateFromClick: (anchorValue, eventValue) => {
      calls.push(['navigate', anchorValue.href, eventValue.button]);
      return true;
    },
    stopPlayback: () => calls.push(['stop']),
  };
  const topWindow = {
    location: { origin: 'https://www.christopherbell.dev' },
    document: { querySelector: () => host },
  };
  const frameWindow = {
    top: topWindow,
    location: { origin: 'https://www.christopherbell.dev' },
    document: {},
  };
  const link = anchor('/void');
  const event = primaryClick();
  event.target = { closest: selector => selector === 'a[href]' ? link : null };

  assert.equal(await siteMedia.playSharedFolderMedia({ path: 'music/song.flac' }, frameWindow),
    'playing');
  assert.equal(siteMedia.handleSiteNavigationClick(event, frameWindow), true);
  siteMedia.stopSiteMediaPlayback(frameWindow);
  assert.deepEqual(calls, [
    ['play', 'music/song.flac'],
    ['navigate', '/void', 0],
    ['stop'],
  ]);
});

function fakeMedia() {
  return {
    pauseCalls: 0,
    loadCalls: 0,
    removedSource: false,
    pause() { this.pauseCalls += 1; },
    removeAttribute(name) {
      if (name === 'src') this.removedSource = true;
    },
    load() { this.loadCalls += 1; },
  };
}
