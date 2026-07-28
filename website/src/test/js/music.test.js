import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

import {
  musicCatalog,
  musicQueueMarkup,
  musicTrackMarkup,
} from '../../main/resources/static/js/lib/music.js';

function track(overrides = {}) {
  return {
    id: 'track-1',
    observedToken: 'a'.repeat(64),
    title: 'Song',
    artist: 'Artist',
    albumArtist: 'Artist',
    album: 'Album',
    trackNumber: 1,
    discNumber: 1,
    genre: 'Genre',
    year: 2026,
    durationSeconds: 181,
    audioCodec: 'flac',
    container: 'flac',
    artworkAvailable: true,
    favorite: false,
    excludedFromRadio: false,
    ...overrides,
  };
}

test('Music catalog boundary rejects paths and malformed revisions while preserving safe tracks', () => {
  assert.equal(musicCatalog({
    tracks: [track()], facets: { artists: ['Artist'], albums: [], genres: [], years: [2026] },
  }).tracks[0].title, 'Song');
  assert.throws(() => musicCatalog({ tracks: [track({ id: '../secret' })], facets: {} }));
  assert.throws(() => musicCatalog({
    tracks: [track({ observedToken: 'stale' })], facets: {},
  }));
});

test('Music track and queue markup escape tags and expose writer actions only to writers', () => {
  const malicious = track({ title: '<img src=x onerror=alert(1)>', artist: 'A&B' });
  const listener = musicTrackMarkup(malicious);
  const writer = musicTrackMarkup(malicious, {
    canManage: true, playlists: [{ id: 'playlist-1', name: '<Road>' }],
  });
  const queue = musicQueueMarkup({
    version: 1,
    items: [{ id: 'queue-1', track: malicious, enqueuedByAccountId: 'writer', enqueuedAt: '2026-07-28' }],
  }, { canManage: true });

  assert.doesNotMatch(listener, /<img src=x/);
  assert.match(listener, /&lt;img/);
  assert.doesNotMatch(listener, /data-action="queue"/);
  assert.match(writer, /data-action="queue"/);
  assert.match(writer, /&lt;Road&gt;/);
  assert.match(queue, /data-action="remove-queue"/);
});

test('Music shell exposes responsive library queue dialogs and its dedicated stylesheet', () => {
  const template = fs.readFileSync(
    new URL('../../main/resources/templates/music.html', import.meta.url), 'utf8');
  const css = fs.readFileSync(
    new URL('../../main/resources/static/css/music.css', import.meta.url), 'utf8');

  for (const marker of ['music-workspace', 'music-results', 'music-queue',
    'music-playlist-dialog', 'music-metadata-dialog', '/css/music.css']) {
    assert.match(template, new RegExp(marker));
  }
  assert.match(css, /@media \(max-width: 720px\)/);
  assert.match(css, /grid-template-columns: 220px minmax\(420px, 1fr\) 330px/);
});
