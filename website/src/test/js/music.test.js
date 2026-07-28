import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

import { API } from '../../main/resources/static/js/lib/api.js';
import {
  musicCatalog,
  musicCatalogParameters,
  musicPageNumbers,
  musicPaginationMarkup,
  musicQueueMarkup,
  musicTrackMarkup,
  musicViewFilter,
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

function catalog(overrides = {}) {
  return {
    tracks: [track()],
    facets: { artists: ['Artist'], albums: [], genres: [], years: [2026] },
    page: 0,
    size: 50,
    totalTracks: 1,
    totalPages: 1,
    ...overrides,
  };
}

test('Music catalog boundary rejects paths and malformed revisions while preserving safe tracks', () => {
  assert.equal(musicCatalog(catalog()).tracks[0].title, 'Song');
  assert.throws(() => musicCatalog(catalog({ tracks: [track({ id: '../secret' })] })));
  assert.throws(() => musicCatalog(catalog({
    tracks: [track({ observedToken: 'stale' })],
  })));
});

test('Music catalog boundary validates internally consistent page metadata', () => {
  const page = musicCatalog(catalog({
    tracks: [track()], page: 1, totalTracks: 51, totalPages: 2,
  }));

  assert.equal(page.page, 1);
  assert.equal(page.totalTracks, 51);
  assert.throws(() => musicCatalog(catalog({ page: 2, totalTracks: 51, totalPages: 2 })));
  assert.throws(() => musicCatalog(catalog({ size: 0 })));
  assert.throws(() => musicCatalog(catalog({ totalTracks: 0, totalPages: 1 })));
});

test('Music page numbers remain compact while preserving first current and last pages', () => {
  assert.deepEqual(musicPageNumbers(0, 1), [0]);
  assert.deepEqual(musicPageNumbers(0, 31), [0, 1, 2, 30]);
  assert.deepEqual(musicPageNumbers(15, 31), [0, 14, 15, 16, 30]);
  assert.deepEqual(musicPageNumbers(30, 31), [0, 28, 29, 30]);
});

test('Music catalog URL carries bounded page and complete server-side view filters', () => {
  assert.equal(
    API.music.catalog({
      q: 'A&B', artist: 'Artist', page: 3, size: 50,
      favorite: true, playlistId: 'road trip',
    }),
    '/api/music/2026-07-28/catalog?page=3&size=50&q=A%26B&artist=Artist&favorite=true&playlistId=road+trip');
});

test('Music view filters keep Favorites and playlists server-side', () => {
  assert.deepEqual(musicViewFilter('all'), {});
  assert.deepEqual(musicViewFilter('favorites'), { favorite: true });
  assert.deepEqual(musicViewFilter('playlist:playlist-1'), { playlistId: 'playlist-1' });
  assert.throws(() => musicViewFilter('playlist:../secret'));
  assert.throws(() => musicViewFilter('unknown'));
});

test('Music catalog parameters preserve search filters and reset only the requested page', () => {
  assert.deepEqual(musicCatalogParameters({
    view: 'favorites', page: 3, q: 'mix', artist: 'Artist', album: '', genre: 'Rock',
  }), {
    q: 'mix', artist: 'Artist', album: '', genre: 'Rock',
    page: 3, size: 50, favorite: true,
  });
  assert.deepEqual(musicCatalogParameters({
    view: 'playlist:playlist-1', page: 0, q: '', artist: '', album: '', genre: '',
  }), {
    q: '', artist: '', album: '', genre: '',
    page: 0, size: 50, playlistId: 'playlist-1',
  });
});

test('Music pagination markup exposes current previous next and compact page controls', () => {
  const middle = musicPaginationMarkup({ page: 15, totalPages: 31 });
  const first = musicPaginationMarkup({ page: 0, totalPages: 31 });

  assert.match(middle, /data-page="14"[^>]*>Previous/);
  assert.match(middle, /data-page="15"[^>]*aria-current="page"[^>]*>16/);
  assert.match(middle, /data-page="16"[^>]*>Next/);
  assert.match(middle, /music-page-gap/);
  assert.match(first, /disabled[^>]*>Previous/);
  assert.doesNotMatch(middle, /onclick|<script/i);
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
    'music-pagination', 'music-playlist-dialog', 'music-metadata-dialog', '/css/music.css']) {
    assert.match(template, new RegExp(marker));
  }
  assert.match(css, /@media \(max-width: 720px\)/);
  assert.match(css, /grid-template-columns: 220px minmax\(420px, 1fr\) 330px/);
  assert.match(css, /\.music-pagination/);
});
