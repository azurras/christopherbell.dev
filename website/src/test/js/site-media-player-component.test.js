import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

globalThis.HTMLElement = class {};
globalThis.customElements = {
  registry: new Map(),
  get(name) { return this.registry.get(name); },
  define(name, constructor) { this.registry.set(name, constructor); },
};

const { SiteMediaPlayer } = await import(
  '../../main/resources/static/js/components/site-media-player.js');

test('failed Play resync is consumed without an immediate repeated radio operation', async () => {
  const player = Object.create(SiteMediaPlayer.prototype);
  player.radioController = new AbortController();
  player.radioGeneration = 7;
  player.radioPlayRequested = false;
  player.radioSyncPromise = null;
  let operationCalls = 0;
  player.performRadioSync = () => {
    operationCalls += 1;
    return operationCalls === 1
      ? Promise.reject(new Error('Radio request failed'))
      : new Promise(() => {});
  };
  player.handleRadioSyncError = () => {};

  assert.equal(await player.syncRadio({ requestPlay: true }), false);
  await Promise.resolve();

  assert.equal(operationCalls, 1);
  assert.equal(player.radioPlayRequested, false);
  assert.equal(player.radioSyncPromise, null);
});

test('component keeps playing intent when restored autoplay emits pause and rejects', async () => {
  const storage = memoryStorage();
  globalThis.sessionStorage = storage;
  const media = fakeMedia();
  media.play = () => {
    media.dispatch('pause');
    return Promise.reject(new Error('Autoplay blocked'));
  };
  const playback = {
    descriptor: { kind: 'AUDIO', title: 'Song.flac', path: 'music/Song.flac' },
    media,
    signal: new AbortController().signal,
  };
  const player = Object.create(SiteMediaPlayer.prototype);
  player.session = { snapshot: () => playback };
  player.resumeByMedia = new WeakMap([[media, {
    descriptor: playback.descriptor,
    positionSeconds: 81,
    wasPlaying: true,
    playbackRate: 1,
    muted: false,
    volume: 1,
  }]]);
  player.sourceVersionByMedia = new WeakMap();
  player.playbackIntentByMedia = new WeakMap([[media, true]]);
  player.pendingPlaybackStart = new WeakSet();
  player.syncControls = () => {};
  player.cancelGestureResume = () => {};
  player.setStatus = message => { player.status = message; };
  player.armGestureResume = () => { player.gestureArmed = true; };

  player.bindPlaybackEvents(playback);
  player.resumePlaybackWhenReady(playback);
  media.dispatch('loadedmetadata');
  await new Promise(resolve => setImmediate(resolve));

  assert.equal(JSON.parse(storage.value()).wasPlaying, true);
  assert.equal(player.pendingPlaybackStart.has(media), true);
  assert.equal(player.gestureArmed, true);
  assert.equal(player.status, 'Tap anywhere to continue');
});

test('component gesture retry ignores its Play control and resumes from page content once', async () => {
  const eventTarget = fakeEventTarget();
  globalThis.document = eventTarget;
  let playCalls = 0;
  const media = { play: () => { playCalls += 1; return Promise.resolve(); } };
  const playback = { media, signal: new AbortController().signal };
  const player = Object.create(SiteMediaPlayer.prototype);
  player.session = { snapshot: () => playback };
  player.pendingPlaybackStart = new WeakSet([media]);
  player.playbackIntentByMedia = new WeakMap();
  player.syncControls = () => {};
  player.persistPlayback = () => true;
  player.setStatus = () => {};

  player.armGestureResume(playback);
  eventTarget.dispatch('pointerdown', {
    target: { closest: selector => selector.includes('play') ? {} : null },
  });
  assert.equal(playCalls, 0);

  eventTarget.dispatch('pointerdown', { target: { closest: () => null } });
  assert.equal(playCalls, 1);
  await Promise.resolve();
  assert.equal(player.playbackIntentByMedia.get(media), true);
  assert.equal(eventTarget.eventNames().length, 0);
});

test('component renders tag text literally and revokes replaced cover artwork', () => {
  const originalDocument = globalThis.document;
  const originalCreateObjectUrl = URL.createObjectURL;
  const originalRevokeObjectUrl = URL.revokeObjectURL;
  const revoked = [];
  let nextArtworkId = 0;
  URL.createObjectURL = () => `blob:cover-${++nextArtworkId}`;
  URL.revokeObjectURL = url => revoked.push(url);
  globalThis.document = {
    createElement(tagName) {
      return {
        tagName,
        setAttribute(name, value) { this[name] = value; },
      };
    },
  };
  try {
    const title = {};
    const metadata = {};
    const mediaHost = { replaceChildren(...children) { this.children = children; } };
    const media = {};
    const playback = {
      descriptor: { kind: 'AUDIO', title: 'fallback.flac' },
      media,
    };
    const player = Object.create(SiteMediaPlayer.prototype);
    player.session = { snapshot: () => playback };
    player.artworkUrl = null;
    player.setMediaSessionMetadata = () => {};
    player.querySelector = selector => ({
      '[data-site-player-title]': title,
      '[data-site-player-track-metadata]': metadata,
      '[data-site-player-media]': mediaHost,
    })[selector];

    player.renderAudioPresentation(playback, {
      title: '<img src=x onerror=alert(1)>',
      artist: '<b>Artist</b>',
      album: 'Album',
      picture: { type: 'image/png', bytes: new Uint8Array([1, 2, 3]) },
    });
    assert.equal(title.textContent, '<img src=x onerror=alert(1)>');
    assert.equal(metadata.textContent, '<b>Artist</b> · Album');
    assert.equal(mediaHost.children[0].tagName, 'img');
    assert.equal(mediaHost.children[1], media);

    player.renderAudioPresentation(playback, null);
    assert.deepEqual(revoked, ['blob:cover-1']);
    assert.equal(mediaHost.children[0].tagName, 'span');
  } finally {
    globalThis.document = originalDocument;
    URL.createObjectURL = originalCreateObjectUrl;
    URL.revokeObjectURL = originalRevokeObjectUrl;
  }
});

test('component disconnect clears stale Media Session presentation', () => {
  globalThis.window = { removeEventListener() {} };
  const player = Object.create(SiteMediaPlayer.prototype);
  let cleared = false;
  player.cancelGestureResume = () => {};
  player.releaseArtwork = () => {};
  player.clearMediaSessionMetadata = () => { cleared = true; };
  player.disconnectedCallback();
  assert.equal(cleared, true);
});

test('component publishes exact title artist album and artwork to Media Session', () => {
  const previousNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  const previousMediaMetadata = globalThis.MediaMetadata;
  const mediaSession = {};
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: { mediaSession },
  });
  globalThis.MediaMetadata = class {
    constructor(details) { Object.assign(this, details); }
  };
  try {
    const player = Object.create(SiteMediaPlayer.prototype);
    player.artworkUrl = 'blob:album-cover';
    player.setMediaSessionMetadata({
      title: 'Song',
      artist: '',
      album: 'Album Only',
      subtitle: 'Album Only',
      picture: { type: 'image/jpeg' },
    });
    assert.deepEqual({
      title: mediaSession.metadata.title,
      artist: mediaSession.metadata.artist,
      album: mediaSession.metadata.album,
      artwork: mediaSession.metadata.artwork,
    }, {
      title: 'Song',
      artist: '',
      album: 'Album Only',
      artwork: [{ src: 'blob:album-cover', type: 'image/jpeg' }],
    });
  } finally {
    if (previousNavigator) Object.defineProperty(globalThis, 'navigator', previousNavigator);
    else delete globalThis.navigator;
    globalThis.MediaMetadata = previousMediaMetadata;
  }
});

test('vendored metadata reader exposes the pinned range adapter interface', () => {
  const source = fs.readFileSync(
    'website/src/main/resources/static/vendor/jsmediatags-3.9.7.min.js', 'utf8');
  const context = { clearTimeout, setTimeout, XMLHttpRequest: class {} };
  vm.runInNewContext(source, context);
  assert.equal(typeof context.jsmediatags?.Reader, 'function');
  assert.equal(typeof context.jsmediatags.Reader.prototype._findFileReader, 'function');
  assert.equal(typeof context.jsmediatags.Reader.prototype.setFileReader, 'function');
});

function fakeMedia() {
  const target = fakeEventTarget();
  return Object.assign(target, {
    currentTime: 0,
    duration: 240,
    paused: true,
    ended: false,
    playbackRate: 1,
    muted: false,
    volume: 1,
  });
}

function fakeEventTarget() {
  const listeners = new Map();
  return {
    addEventListener(name, listener) {
      const namedListeners = listeners.get(name) || [];
      namedListeners.push(listener);
      listeners.set(name, namedListeners);
    },
    removeEventListener(name, listener) {
      const remaining = (listeners.get(name) || []).filter(candidate => candidate !== listener);
      if (remaining.length > 0) listeners.set(name, remaining);
      else listeners.delete(name);
    },
    dispatch(name, event = undefined) {
      for (const listener of [...(listeners.get(name) || [])]) listener(event);
    },
    eventNames() { return [...listeners.keys()].sort(); },
  };
}

function memoryStorage() {
  let value = null;
  return {
    getItem() { return value; },
    setItem(_, next) { value = next; },
    removeItem() { value = null; },
    value() { return value; },
  };
}
