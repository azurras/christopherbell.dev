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
    playSharedFolderRadio: () => {
      calls.push(['radio']);
      return Promise.resolve('live');
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
  assert.equal(await siteMedia.playSharedFolderRadio(frameWindow), 'live');
  assert.equal(siteMedia.handleSiteNavigationClick(event, frameWindow), true);
  siteMedia.stopSiteMediaPlayback(frameWindow);
  assert.deepEqual(calls, [
    ['play', 'music/song.flac'],
    ['radio'],
    ['navigate', '/void', 0],
    ['stop'],
  ]);
});

test('radio response boundary accepts only complete empty or playable audio states', () => {
  assert.equal(typeof siteMedia.validateSiteRadioResponse, 'function');
  const playing = validRadioResponse();

  assert.deepEqual(siteMedia.validateSiteRadioResponse({ status: 'EMPTY', playback: null }), {
    status: 'EMPTY', playback: null,
  });
  assert.deepEqual(siteMedia.validateSiteRadioResponse(playing), playing);

  const malformed = [
    { ...playing, status: 'EMPTY' },
    { ...playing, playback: { ...playing.playback, stationSequence: 0 } },
    { ...playing, playback: { ...playing.playback, startedAt: 'not-an-instant' } },
    { ...playing, playback: { ...playing.playback, positionSeconds: -1 } },
    { ...playing, playback: { ...playing.playback, durationSeconds: 86_401 } },
    { ...playing, playback: {
      ...playing.playback,
      entry: { ...playing.playback.entry, path: '../Song.mp3' },
    } },
    { ...playing, playback: {
      ...playing.playback,
      entry: { ...playing.playback.entry, name: 'Other.mp3' },
    } },
    { ...playing, playback: {
      ...playing.playback,
      entry: { ...playing.playback.entry, previewKind: 'VIDEO' },
    } },
  ];
  for (const response of malformed) {
    assert.throws(() => siteMedia.validateSiteRadioResponse(response), /invalid radio response/i);
  }
});

test('radio synchronization replaces identity changes and corrects drift only above three seconds', () => {
  assert.equal(typeof siteMedia.siteRadioSyncDecision, 'function');
  const response = validRadioResponse();
  const current = { stationSequence: 7, path: 'Music/Song.mp3' };

  assert.deepEqual(siteMedia.siteRadioSyncDecision(null, response, 0), {
    action: 'REPLACE', targetPositionSeconds: 12.5,
  });
  assert.deepEqual(siteMedia.siteRadioSyncDecision(current, response, 9.5), {
    action: 'KEEP', targetPositionSeconds: 12.5,
  });
  assert.deepEqual(siteMedia.siteRadioSyncDecision(current, response, 9.49), {
    action: 'SEEK', targetPositionSeconds: 12.5,
  });
  assert.deepEqual(siteMedia.siteRadioSyncDecision(
    { ...current, path: 'Music/Other.mp3' }, response, 12.5,
  ), { action: 'REPLACE', targetPositionSeconds: 12.5 });
  assert.deepEqual(siteMedia.siteRadioSyncDecision(
    current, { status: 'EMPTY', playback: null }, 12.5,
  ), { action: 'EMPTY', targetPositionSeconds: null });
});

test('radio resume keeps local intent and preferences while joining the current server position', () => {
  assert.equal(typeof siteMedia.siteRadioResumeState, 'function');
  const resume = {
    descriptor: {
      mode: 'RADIO', kind: 'AUDIO', title: 'Old.mp3', path: 'Music/Old.mp3',
    },
    positionSeconds: 400,
    wasPlaying: false,
    playbackRate: 2,
    muted: true,
    volume: 0.25,
  };

  assert.deepEqual(siteMedia.siteRadioResumeState(resume, validRadioResponse().playback), {
    descriptor: {
      mode: 'RADIO', kind: 'AUDIO', title: 'Song.mp3', path: 'Music/Song.mp3',
      stationSequence: 7,
    },
    positionSeconds: 12.5,
    wasPlaying: false,
    playbackRate: 1,
    muted: true,
    volume: 0.25,
  });
});

test('radio duration report accepts inclusive backend bounds for the exact identity', () => {
  assert.equal(typeof siteMedia.siteRadioDurationReport, 'function');
  const playback = validRadioResponse().playback;

  assert.deepEqual(siteMedia.siteRadioDurationReport(playback, 1), {
    stationSequence: 7, path: 'Music/Song.mp3', durationSeconds: 1,
  });
  assert.deepEqual(siteMedia.siteRadioDurationReport(playback, 86_400), {
    stationSequence: 7, path: 'Music/Song.mp3', durationSeconds: 86_400,
  });
  for (const duration of [0, 86_401, Number.NaN, Number.POSITIVE_INFINITY]) {
    assert.equal(siteMedia.siteRadioDurationReport(playback, duration), null);
  }
});

test('radio duration reporter emits at most once for each media source and sequence', async () => {
  assert.equal(typeof siteMedia.createSiteRadioDurationReporter, 'function');
  const reports = [];
  const reporter = siteMedia.createSiteRadioDurationReporter({
    report: request => { reports.push(request); return Promise.resolve(); },
  });
  const playback = validRadioResponse().playback;

  assert.equal(await reporter.loaded(playback, '/preview?track=Song.mp3', 120), true);
  assert.equal(await reporter.loaded(playback, '/preview?track=Song.mp3', 120), false);
  assert.equal(await reporter.loaded(playback, '/media/jobs/job-1/stream', 120), true);
  assert.deepEqual(reports, [
    { stationSequence: 7, path: 'Music/Song.mp3', durationSeconds: 120 },
    { stationSequence: 7, path: 'Music/Song.mp3', durationSeconds: 120 },
  ]);
});

test('radio control state disables item-only transport while item playback stays seekable', () => {
  assert.equal(typeof siteMedia.siteMediaControlState, 'function');
  assert.deepEqual(siteMedia.siteMediaControlState({ mode: 'RADIO' }, 120), {
    live: true,
    seekDisabled: true,
    rewindDisabled: true,
    forwardDisabled: true,
    rateDisabled: true,
  });
  assert.deepEqual(siteMedia.siteMediaControlState({ mode: 'ITEM' }, 120), {
    live: false,
    seekDisabled: false,
    rewindDisabled: false,
    forwardDisabled: false,
    rateDisabled: false,
  });
});

test('radio scheduler waits exactly fifteen seconds, serializes polls, and owns teardown', async () => {
  assert.equal(typeof siteMedia.createSiteRadioScheduler, 'function');
  const scheduled = [];
  const cancelled = [];
  let pollCalls = 0;
  let finishPoll;
  const scheduler = siteMedia.createSiteRadioScheduler({
    poll: () => {
      pollCalls += 1;
      return new Promise(resolve => { finishPoll = resolve; });
    },
    schedule: (callback, delayMilliseconds) => {
      const timer = { callback, delayMilliseconds };
      scheduled.push(timer);
      return timer;
    },
    cancel: timer => cancelled.push(timer),
  });

  scheduler.start();
  assert.equal(scheduled.length, 1);
  assert.equal(scheduled[0].delayMilliseconds, 15_000);
  const firstTimer = scheduled.shift();
  firstTimer.callback();
  firstTimer.callback();
  assert.equal(pollCalls, 1);
  assert.equal(scheduled.length, 0);

  finishPoll();
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(scheduled.length, 1);
  assert.equal(scheduled[0].delayMilliseconds, 15_000);

  const pendingTimer = scheduled[0];
  scheduler.stop();
  assert.deepEqual(cancelled, [pendingTimer]);
  pendingTimer.callback();
  assert.equal(pollCalls, 1);
});

test('same-tab resume storage round-trips only validated non-secret playback state', () => {
  assert.equal(typeof siteMedia.saveSiteMediaResume, 'function');
  assert.equal(typeof siteMedia.readSiteMediaResume, 'function');
  const storage = memoryStorage();
  const descriptor = {
    kind: 'AUDIO',
    title: 'Song.flac',
    path: 'music/Song.flac',
    token: 'must-not-be-saved',
    streamUrl: '/api/shared-folder/secret-stream',
  };
  const media = {
    currentTime: 134.5,
    paused: false,
    ended: false,
    playbackRate: 1.25,
    muted: true,
    volume: 0.4,
  };

  assert.equal(siteMedia.saveSiteMediaResume(storage, descriptor, media), true);
  assert.deepEqual(siteMedia.readSiteMediaResume(storage), {
    descriptor: { mode: 'ITEM', kind: 'AUDIO', title: 'Song.flac', path: 'music/Song.flac' },
    positionSeconds: 134.5,
    wasPlaying: true,
    playbackRate: 1.25,
    muted: true,
    volume: 0.4,
  });
  assert.equal(storage.value().includes('must-not-be-saved'), false);
  assert.equal(storage.value().includes('secret-stream'), false);
});

test('same-tab resume storage rejects malformed state and clears completed playback', () => {
  assert.equal(typeof siteMedia.clearSiteMediaResume, 'function');
  const storage = memoryStorage('{"version":1,"descriptor":{"kind":"PDF"}}');

  assert.equal(siteMedia.readSiteMediaResume(storage), null);
  assert.equal(storage.value(), null);

  siteMedia.saveSiteMediaResume(storage, {
    kind: 'VIDEO', title: 'Clip.mkv', path: 'video/Clip.mkv',
  }, {
    currentTime: 20, paused: true, ended: true, playbackRate: 1, muted: false, volume: 1,
  });
  assert.equal(storage.value(), null);
});

test('same-tab resume treats legacy descriptors as items and preserves explicit radio mode', () => {
  const legacyStorage = memoryStorage(JSON.stringify({
    version: 1,
    descriptor: { kind: 'AUDIO', title: 'Legacy.mp3', path: 'Music/Legacy.mp3' },
    positionSeconds: 42,
    wasPlaying: true,
    playbackRate: 1,
    muted: false,
    volume: 1,
  }));
  assert.equal(siteMedia.readSiteMediaResume(legacyStorage).descriptor.mode, 'ITEM');

  const radioStorage = memoryStorage();
  assert.equal(siteMedia.saveSiteMediaResume(radioStorage, {
    mode: 'RADIO', kind: 'AUDIO', title: 'Song.mp3', path: 'Music/Song.mp3',
  }, {
    currentTime: 12.5, paused: true, ended: false, playbackRate: 1, muted: false, volume: 0.5,
  }), true);
  assert.equal(siteMedia.readSiteMediaResume(radioStorage).descriptor.mode, 'RADIO');
});

test('same-tab resume preserves explicit playing intent while restored media is paused', () => {
  const storage = memoryStorage();

  assert.equal(siteMedia.saveSiteMediaResume(storage, {
    kind: 'AUDIO', title: 'Song.flac', path: 'music/Song.flac',
  }, {
    currentTime: 134.5,
    paused: true,
    ended: false,
    playbackRate: 1,
    muted: false,
    volume: 1,
  }, { wasPlaying: true }), true);

  assert.equal(siteMedia.readSiteMediaResume(storage).wasPlaying, true);
});

test('resume application restores position and preferences before requesting playback', async () => {
  assert.equal(typeof siteMedia.applySiteMediaResume, 'function');
  const calls = [];
  const media = {
    duration: 300,
    currentTime: 0,
    playbackRate: 1,
    muted: false,
    volume: 1,
    play() {
      calls.push(['play', this.currentTime, this.playbackRate, this.muted, this.volume]);
      return Promise.resolve();
    },
  };

  await siteMedia.applySiteMediaResume(media, {
    descriptor: { kind: 'AUDIO', title: 'Song.flac', path: 'music/Song.flac' },
    positionSeconds: 134.5,
    wasPlaying: true,
    playbackRate: 1.25,
    muted: true,
    volume: 0.4,
  });

  assert.deepEqual(calls, [['play', 134.5, 1.25, true, 0.4]]);
});

test('custom media controls format time, clamp skips, and cycle playback speed', () => {
  assert.equal(typeof siteMedia.formatSiteMediaTime, 'function');
  assert.equal(typeof siteMedia.seekSiteMediaBy, 'function');
  assert.equal(typeof siteMedia.nextSiteMediaPlaybackRate, 'function');
  assert.equal(siteMedia.formatSiteMediaTime(134.9), '2:14');
  assert.equal(siteMedia.formatSiteMediaTime(3605), '1:00:05');
  assert.equal(siteMedia.formatSiteMediaTime(Number.NaN), '0:00');

  const media = { currentTime: 4, duration: 100 };
  assert.equal(siteMedia.seekSiteMediaBy(media, -10), 0);
  media.currentTime = 96;
  assert.equal(siteMedia.seekSiteMediaBy(media, 10), 100);

  assert.equal(siteMedia.nextSiteMediaPlaybackRate(1), 1.25);
  assert.equal(siteMedia.nextSiteMediaPlaybackRate(1.25), 1.5);
  assert.equal(siteMedia.nextSiteMediaPlaybackRate(1.5), 2);
  assert.equal(siteMedia.nextSiteMediaPlaybackRate(2), 1);
});

test('custom media transport toggles playback and mute through the media boundary', async () => {
  assert.equal(typeof siteMedia.toggleSiteMediaPlayback, 'function');
  assert.equal(typeof siteMedia.toggleSiteMediaMute, 'function');
  const calls = [];
  const media = {
    paused: true,
    ended: false,
    muted: false,
    play() {
      calls.push('play');
      this.paused = false;
      return Promise.resolve();
    },
    pause() {
      calls.push('pause');
      this.paused = true;
    },
  };

  await siteMedia.toggleSiteMediaPlayback(media);
  await siteMedia.toggleSiteMediaPlayback(media);
  assert.equal(siteMedia.toggleSiteMediaMute(media), true);
  assert.deepEqual(calls, ['play', 'pause']);
});

test('blocked autoplay resumes synchronously on the first pointer or keyboard gesture', async () => {
  assert.equal(typeof siteMedia.armSiteMediaGestureResume, 'function');
  const target = fakeEventTarget();
  const calls = [];
  const media = {
    play() {
      calls.push('play');
      return Promise.resolve();
    },
  };

  const cancel = siteMedia.armSiteMediaGestureResume(media, target, () => calls.push('started'));
  target.dispatch('pointerdown');
  assert.deepEqual(calls, ['play']);
  await Promise.resolve();
  assert.deepEqual(calls, ['play', 'started']);
  assert.deepEqual(target.eventNames(), []);

  cancel();
});

test('gesture resume can leave the play control to its own click handler', async () => {
  const target = fakeEventTarget();
  const calls = [];
  const media = { play: () => { calls.push('play'); return Promise.resolve(); } };
  const shouldResume = event => event?.target !== 'play-control';

  siteMedia.armSiteMediaGestureResume(
    media, target, () => calls.push('started'), shouldResume);
  target.dispatch('pointerdown', { target: 'play-control' });
  await Promise.resolve();
  assert.deepEqual(calls, []);

  target.dispatch('pointerdown', { target: 'page-content' });
  assert.deepEqual(calls, ['play']);
  await Promise.resolve();
  assert.deepEqual(calls, ['play', 'started']);
});

test('audio tag boundary accepts safe details and artwork while rejecting active artwork', () => {
  assert.equal(typeof siteMedia.normalizeSiteAudioMetadata, 'function');
  assert.deepEqual(siteMedia.normalizeSiteAudioMetadata({
    title: '  Get Yo Shine On  ',
    artist: ' B.G. ',
    album: ' The Heart of tha Streetz, Vol. 2 ',
    picture: { format: 'image/jpeg', data: [0, 127, 255] },
  }), {
    title: 'Get Yo Shine On',
    artist: 'B.G.',
    album: 'The Heart of tha Streetz, Vol. 2',
    picture: { type: 'image/jpeg', bytes: new Uint8Array([0, 127, 255]) },
  });

  assert.deepEqual(siteMedia.normalizeSiteAudioMetadata({
    title: '<b>Rendered as text</b>',
    picture: { format: 'image/svg+xml', data: [60, 115, 118, 103] },
  }), {
    title: '<b>Rendered as text</b>', artist: null, album: null, picture: null,
  });
});

test('audio presentation prefers tags and falls back cleanly to the file name', () => {
  assert.equal(typeof siteMedia.siteAudioPresentation, 'function');
  assert.deepEqual(siteMedia.siteAudioPresentation({
    title: 'Get Yo Shine On', artist: 'B.G.', album: 'The Heart of tha Streetz, Vol. 2',
    picture: null,
  }, '24 - B.G. - Get Yo Shine On 2005.flac'), {
    title: 'Get Yo Shine On',
    artist: 'B.G.',
    album: 'The Heart of tha Streetz, Vol. 2',
    subtitle: 'B.G. · The Heart of tha Streetz, Vol. 2',
    picture: null,
  });
  assert.deepEqual(siteMedia.siteAudioPresentation(null, 'unknown.flac'), {
    title: 'unknown.flac', artist: '', album: '', subtitle: '', picture: null,
  });
});

test('audio metadata reader requests only display tags and validates the result', async () => {
  assert.equal(typeof siteMedia.readSiteAudioMetadata, 'function');
  const observed = {};
  class Reader {
    constructor(url) { observed.url = url; }
    _findFileReader() { return class DefaultFileReader {}; }
    setFileReader() { return this; }
    setTagsToRead(tags) {
      observed.tags = tags;
      return this;
    }
    read(callbacks) {
      callbacks.onSuccess({ tags: { title: 'Song', artist: 'Artist', album: 'Album' } });
    }
  }

  const metadata = await siteMedia.readSiteAudioMetadata(
    '/api/shared-folder/2026-07-17/preview/music%2FSong.flac', async () => Reader);

  assert.deepEqual(observed, {
    url: '/api/shared-folder/2026-07-17/preview/music%2FSong.flac',
    tags: ['title', 'artist', 'album', 'picture'],
  });
  assert.deepEqual(metadata, {
    title: 'Song', artist: 'Artist', album: 'Album', picture: null,
  });
});

test('audio metadata reader rejects oversized parser ranges before network I/O', async () => {
  const loadedRanges = [];
  class DefaultFileReader {
    constructor() {
      this._size = 20 * 1024 * 1024;
      this._fileData = { hasDataRange: () => false };
    }
    init(callbacks) { callbacks.onSuccess(); }
    getSize() { return this._size; }
    _roundRangeToChunkMultiple(range) { return range; }
    loadRange(range, callbacks) {
      loadedRanges.push(range);
      callbacks.onSuccess();
    }
  }
  class Reader {
    _findFileReader() { return DefaultFileReader; }
    setFileReader(FileReader) { this.FileReader = FileReader; return this; }
    setTagsToRead() { return this; }
    read(callbacks) {
      const file = new this.FileReader('https://www.christopherbell.dev/media.flac');
      file.init({
        onSuccess: () => file.loadRange([0, 7 * 1024 * 1024], callbacks),
        onError: callbacks.onError,
      });
    }
  }

  await assert.rejects(
    siteMedia.readSiteAudioMetadata('https://www.christopherbell.dev/media.flac',
      async () => Reader),
    /byte budget/i);
  assert.deepEqual(loadedRanges, []);
});

test('audio metadata reader aborts parser requests with the playback lifetime', async () => {
  const controller = new AbortController();
  let requestAborted = false;
  let markRequestStarted;
  const requestStarted = new Promise(resolve => { markRequestStarted = resolve; });
  class DefaultFileReader {
    _createXHRObject() {
      return {
        abort() { requestAborted = true; },
        addEventListener() {},
      };
    }
  }
  class Reader {
    _findFileReader() { return DefaultFileReader; }
    setFileReader(FileReader) { this.FileReader = FileReader; return this; }
    setTagsToRead() { return this; }
    read() {
      const file = new this.FileReader('https://www.christopherbell.dev/media.m4a');
      file._createXHRObject();
      markRequestStarted();
    }
  }

  const result = siteMedia.readSiteAudioMetadata(
    'https://www.christopherbell.dev/media.m4a', async () => Reader, controller.signal);
  await requestStarted;
  controller.abort();

  await assert.rejects(result, error => error?.name === 'AbortError');
  assert.equal(requestAborted, true);
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

function memoryStorage(initialValue = null) {
  let value = initialValue;
  return {
    getItem() { return value; },
    setItem(_key, next) { value = next; },
    removeItem() { value = null; },
    value() { return value; },
  };
}

function fakeEventTarget() {
  const listeners = new Map();
  return {
    addEventListener(name, listener) { listeners.set(name, listener); },
    removeEventListener(name, listener) {
      if (listeners.get(name) === listener) listeners.delete(name);
    },
    dispatch(name, event = undefined) { listeners.get(name)?.(event); },
    eventNames() { return [...listeners.keys()].sort(); },
  };
}

function validRadioResponse() {
  return {
    status: 'PLAYING',
    playback: {
      stationSequence: 7,
      startedAt: '2026-07-25T12:00:00Z',
      positionSeconds: 12.5,
      durationSeconds: null,
      entry: {
        name: 'Song.mp3',
        path: 'Music/Song.mp3',
        type: 'FILE',
        size: 12_345,
        modifiedAt: '2026-07-24T12:00:00Z',
        previewKind: 'AUDIO',
        observedToken: 'proof',
      },
    },
  };
}
