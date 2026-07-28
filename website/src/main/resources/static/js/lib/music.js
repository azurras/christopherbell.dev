import { API } from './api.js';
import { sanitize } from './util.js';

const TEXT_LIMIT = 512;

function optionalText(value) {
  return value === null || value === undefined
    ? null
    : typeof value === 'string' && value.length <= TEXT_LIMIT ? value : undefined;
}

/** Validate one catalog track before it reaches markup, URLs, or the player. */
export function musicTrack(value) {
  const text = ['title', 'artist', 'albumArtist', 'album', 'genre']
    .map(key => optionalText(value?.[key]));
  if (typeof value?.id !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/u.test(value.id)
      || typeof value.observedToken !== 'string' || !/^[0-9a-f]{64}$/u.test(value.observedToken)
      || typeof text[0] !== 'string' || text.some(item => item === undefined)
      || !Number.isFinite(value.durationSeconds) || value.durationSeconds <= 0
      || value.durationSeconds > 604800
      || typeof value.artworkAvailable !== 'boolean'
      || typeof value.favorite !== 'boolean'
      || typeof value.excludedFromRadio !== 'boolean') {
    throw new Error('Music returned an invalid track.');
  }
  return Object.freeze({
    ...value,
    title: text[0], artist: text[1], albumArtist: text[2], album: text[3], genre: text[4],
  });
}

export function musicCatalog(value) {
  if (!Array.isArray(value?.tracks) || typeof value?.facets !== 'object') {
    throw new Error('Music returned an invalid catalog.');
  }
  return Object.freeze({
    tracks: Object.freeze(value.tracks.map(musicTrack)),
    facets: Object.freeze({
      artists: strings(value.facets.artists),
      albums: strings(value.facets.albums),
      genres: strings(value.facets.genres),
      years: Object.freeze(Array.isArray(value.facets.years)
        ? value.facets.years.filter(Number.isSafeInteger).slice(0, 500) : []),
    }),
  });
}

export function musicQueue(value) {
  if (!Number.isSafeInteger(value?.version) || value.version < 0 || !Array.isArray(value.items)) {
    throw new Error('Music returned an invalid queue.');
  }
  return Object.freeze({
    version: value.version,
    items: Object.freeze(value.items.slice(0, 1000).map(item => {
      if (typeof item?.id !== 'string' || !/^[A-Za-z0-9_-]{1,100}$/u.test(item.id)) {
        throw new Error('Music returned an invalid queue item.');
      }
      return Object.freeze({ ...item, track: musicTrack(item.track) });
    })),
  });
}

export function musicPlaylists(value) {
  if (!Array.isArray(value)) throw new Error('Music returned invalid playlists.');
  return Object.freeze(value.slice(0, 100).map(playlist => {
    if (typeof playlist?.id !== 'string' || !/^[A-Za-z0-9_-]{1,100}$/u.test(playlist.id)
        || typeof playlist.name !== 'string' || playlist.name.length < 1 || playlist.name.length > 100
        || !Number.isSafeInteger(playlist.version) || playlist.version < 0
        || !Array.isArray(playlist.trackIds) || playlist.trackIds.length > 1000
        || playlist.trackIds.some(id => typeof id !== 'string'
          || !/^[A-Za-z0-9_-]{1,128}$/u.test(id))) {
      throw new Error('Music returned an invalid playlist.');
    }
    return Object.freeze({ ...playlist, trackIds: Object.freeze([...playlist.trackIds]) });
  }));
}

export function formatMusicDuration(seconds) {
  const total = Number.isFinite(seconds) ? Math.max(0, Math.round(seconds)) : 0;
  const minutes = Math.floor(total / 60);
  return `${minutes}:${String(total % 60).padStart(2, '0')}`;
}

export function musicTrackMarkup(track, { canManage = false, playlists = [] } = {}) {
  const value = musicTrack(track);
  const subtitle = [value.artist, value.album].filter(Boolean).join(' · ') || 'Unknown artist';
  const art = value.artworkAvailable
    ? `<img src="${sanitize(API.music.artwork(value.id))}" alt="" loading="lazy" />`
    : '<span aria-hidden="true">♫</span>';
  return `<article class="music-track" data-track-id="${sanitize(value.id)}">
    <button class="music-track-play" type="button" data-action="play" aria-label="Play ${sanitize(value.title)}">▶</button>
    <div class="music-track-art">${art}</div>
    <div class="music-track-copy"><strong>${sanitize(value.title)}</strong><span>${sanitize(subtitle)}</span></div>
    <time>${formatMusicDuration(value.durationSeconds)}</time>
    <div class="music-track-actions">
      ${canManage ? `<button type="button" data-action="favorite" aria-pressed="${value.favorite}">${value.favorite ? '★' : '☆'}</button>
      <button type="button" data-action="queue">Queue</button>
      <button type="button" data-action="exclude" aria-pressed="${value.excludedFromRadio}">${value.excludedFromRadio ? 'Radio off' : 'Radio on'}</button>
      <select data-action="playlist" aria-label="Add ${sanitize(value.title)} to playlist">
        <option value="">Playlist…</option>${playlists.map(item => `<option value="${sanitize(item.id)}">${sanitize(item.name)}</option>`).join('')}
      </select>
      <button type="button" data-action="edit">Edit</button>` : ''}
    </div>
  </article>`;
}

export function musicQueueMarkup(queue, { canManage = false } = {}) {
  const value = musicQueue(queue);
  if (!value.items.length) return '<p class="music-empty">The shared queue is empty.</p>';
  return value.items.map((item, index) => `<article class="music-queue-item" data-queue-id="${sanitize(item.id)}">
    <span>${index + 1}</span><div><strong>${sanitize(item.track.title)}</strong>
    <small>${sanitize(item.track.artist || 'Unknown artist')}</small></div>
    ${canManage ? '<button type="button" data-action="remove-queue" aria-label="Remove from queue">×</button>' : ''}
  </article>`).join('');
}

function strings(value) {
  return Object.freeze(Array.isArray(value)
    ? value.filter(item => typeof item === 'string' && item.length <= TEXT_LIMIT).slice(0, 500) : []);
}
