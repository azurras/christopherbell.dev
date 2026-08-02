import assert from 'node:assert/strict';
import test from 'node:test';

const loaderModule = await import(
  '../../main/resources/static/js/lib/site-media-loader.js'
).catch(cause => ({ loadFailure: cause }));

test('media loader initializes one runtime for repeated playback actions', async () => {
  assert.ifError(loaderModule.loadFailure);
  const calls = [];
  const boundary = fakeBoundary(calls);
  const loader = loaderModule.createSiteMediaLoader(boundary.options);

  assert.equal(await loader.playMusicTrack({ id: 'one' }), 'track:one');
  assert.equal(await loader.playSharedFolderRadio(), 'shared-radio');

  assert.deepEqual(calls, [
    'component:load',
    'api:load',
    'element:create:site-media-player',
    'element:append:site-media-player',
    'listener:add:click:true',
    'track:one',
    'shared-radio',
  ]);
});

test('media loader retries initialization after a failed dynamic import', async () => {
  assert.ifError(loaderModule.loadFailure);
  let attempts = 0;
  const calls = [];
  const boundary = fakeBoundary(calls, {
    loadApi: async () => {
      attempts += 1;
      calls.push(`api:load:${attempts}`);
      if (attempts === 1) throw new Error('temporary module failure');
      return fakeApi(calls);
    },
  });
  const loader = loaderModule.createSiteMediaLoader(boundary.options);

  await assert.rejects(loader.playMusicRadio(), /temporary module failure/);
  assert.equal(await loader.playMusicRadio(), 'music-radio');
  assert.equal(attempts, 2);
});

test('media loader resumes only when persisted playback exists', async () => {
  assert.ifError(loaderModule.loadFailure);
  const calls = [];
  const boundary = fakeBoundary(calls);
  const loader = loaderModule.createSiteMediaLoader(boundary.options);
  const emptyStorage = { getItem: () => null };
  const resumeStorage = { getItem: key => key === 'cbellSiteMediaResumeV1' ? '{}' : null };

  assert.equal(await loader.resumeSiteMediaIfPresent(emptyStorage), false);
  assert.deepEqual(calls, []);
  assert.equal(await loader.resumeSiteMediaIfPresent(resumeStorage), true);
  assert.equal(calls.filter(value => value === 'api:load').length, 1);
});

test('default media resume checks same-tab session storage', async context => {
  assert.ifError(loaderModule.loadFailure);
  const hadWindow = Object.hasOwn(globalThis, 'window');
  const originalWindow = globalThis.window;
  const hadDocument = Object.hasOwn(globalThis, 'document');
  const originalDocument = globalThis.document;
  context.after(() => {
    if (hadWindow) globalThis.window = originalWindow;
    else delete globalThis.window;
    if (hadDocument) globalThis.document = originalDocument;
    else delete globalThis.document;
  });
  const emptySessionStorage = { getItem: () => null };
  const staleLocalStorage = {
    getItem: key => key === 'cbellSiteMediaResumeV1' ? '{}' : null,
  };
  const documentRoot = {
    body: { appendChild: () => {} },
    head: { appendChild: () => {} },
    querySelector: () => null,
    createElement: tagName => ({ tagName, dataset: {} }),
    addEventListener: () => {},
  };
  globalThis.window = {
    document: documentRoot,
    localStorage: staleLocalStorage,
    sessionStorage: emptySessionStorage,
  };
  globalThis.window.top = globalThis.window;
  globalThis.document = documentRoot;

  assert.equal(await loaderModule.resumeSiteMediaIfPresent(), false);
});

test('media loader stop is a no-op before initialization and contains load failure', async () => {
  assert.ifError(loaderModule.loadFailure);
  const calls = [];
  const boundary = fakeBoundary(calls, {
    loadApi: async () => {
      calls.push('api:load');
      throw new Error('load failed');
    },
  });
  const loader = loaderModule.createSiteMediaLoader(boundary.options);

  await loader.stopSiteMediaPlayback();
  assert.deepEqual(calls, []);
  await assert.rejects(loader.playMusicRadio(), /load failed/);
  await loader.stopSiteMediaPlayback();
});

test('media loader stops an existing top-document player without importing runtime code', async () => {
  assert.ifError(loaderModule.loadFailure);
  const calls = [];
  const boundary = fakeBoundary(calls, {
    findPlayerHost: () => ({ stopPlayback: () => calls.push('existing:stop') }),
  });
  const loader = loaderModule.createSiteMediaLoader(boundary.options);

  await loader.stopSiteMediaPlayback();

  assert.deepEqual(calls, ['existing:stop']);
});

test('media loader installs the versioned player stylesheet once before mounting', async () => {
  assert.ifError(loaderModule.loadFailure);
  const calls = [];
  const boundary = fakeBoundary(calls, {}, { stylesPresent: false });
  const loader = loaderModule.createSiteMediaLoader(boundary.options);

  await loader.playMusicRadio();
  await loader.playMusicRadio();

  assert.deepEqual(calls.filter(value => value.startsWith('style:append:')), [
    'style:append:https://www.christopherbell.dev/version/css/site-media-player.css',
  ]);
  assert.ok(calls.indexOf('element:create:link')
    < calls.indexOf('element:create:site-media-player'));
});

function fakeBoundary(calls, overrides = {}, { stylesPresent = true } = {}) {
  const player = { tagName: 'site-media-player' };
  const documentRoot = {
    body: { appendChild: element => calls.push(`element:append:${element.tagName}`) },
    head: { appendChild: element => calls.push(`style:append:${element.href}`) },
    querySelector: selector => selector === 'link[data-site-media-player-styles]'
      && stylesPresent ? {} : null,
    createElement: tagName => {
      calls.push(`element:create:${tagName}`);
      return { tagName, dataset: {} };
    },
    addEventListener: (name, _listener, capture) => {
      calls.push(`listener:add:${name}:${capture}`);
    },
  };
  const windowRoot = { document: documentRoot };
  windowRoot.top = windowRoot;
  return {
    options: {
      loadComponent: async () => { calls.push('component:load'); },
      loadApi: async () => { calls.push('api:load'); return fakeApi(calls); },
      windowRoot,
      documentRoot,
      findPlayerHost: () => null,
      playerStylesheetUrl:
        'https://www.christopherbell.dev/version/css/site-media-player.css',
      ...overrides,
    },
    player,
  };
}

function fakeApi(calls) {
  return {
    SITE_MEDIA_PLAYER_TAG: 'site-media-player',
    siteMediaPlayerHost: () => null,
    handleSiteNavigationClick: () => {},
    playSharedFolderMedia: entry => `shared:${entry.path}`,
    playSharedFolderRadio: () => { calls.push('shared-radio'); return 'shared-radio'; },
    playMusicTrack: track => { calls.push(`track:${track.id}`); return `track:${track.id}`; },
    playMusicRadio: () => 'music-radio',
    stopSiteMediaPlayback: () => calls.push('stop'),
  };
}
