import { API } from './api.js';
import { sanitize } from './util.js';

const TEXT_LIMIT = 512;

/** Return the effective Music read capability reported by the current-account API. */
export function accountHasMusicRead(account) {
  if (account?.role === 'ADMIN') return true;
  const permissions = new Set(Array.isArray(account?.permissions) ? account.permissions : []);
  return permissions.has('MUSIC_READ') || permissions.has('MUSIC_WRITE');
}

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
  const validPage = Number.isSafeInteger(value?.page) && value.page >= 0;
  const validSize = Number.isSafeInteger(value?.size) && value.size >= 1 && value.size <= 100;
  const validTotal = Number.isSafeInteger(value?.totalTracks) && value.totalTracks >= 0;
  const validPages = Number.isSafeInteger(value?.totalPages) && value.totalPages >= 0;
  const expectedPages = validSize && validTotal ? Math.ceil(value.totalTracks / value.size) : -1;
  const pageInRange = value?.totalPages === 0
    ? value?.page === 0 && value?.tracks?.length === 0
    : value?.page < value?.totalPages;
  if (!Array.isArray(value?.tracks) || typeof value?.facets !== 'object'
      || !validPage || !validSize || !validTotal || !validPages
      || value.totalPages !== expectedPages || !pageInRange || value.tracks.length > value.size) {
    throw new Error('Music returned an invalid catalog.');
  }
  return Object.freeze({
    tracks: Object.freeze(value.tracks.map(musicTrack)),
    facets: musicFacets(value.facets),
    page: value.page,
    size: value.size,
    totalTracks: value.totalTracks,
    totalPages: value.totalPages,
  });
}

/** Return a compact set of zero-based page numbers for accessible pagination controls. */
export function musicPageNumbers(page, totalPages) {
  if (!Number.isSafeInteger(page) || !Number.isSafeInteger(totalPages) || totalPages < 1) return [];
  const last = totalPages - 1;
  const current = Math.max(0, Math.min(last, page));
  if (totalPages <= 7) return Array.from({ length: totalPages }, (_, index) => index);
  if (current <= 1) return [0, 1, 2, last];
  if (current >= last - 1) return [0, last - 2, last - 1, last];
  return [0, current - 1, current, current + 1, last];
}

/** Translate a validated Music sidebar view into server-side catalog constraints. */
export function musicViewFilter(view) {
  if (view === 'all') return {};
  if (view === 'favorites') return { favorite: true };
  if (typeof view === 'string' && view.startsWith('playlist:')) {
    const playlistId = view.substring('playlist:'.length);
    if (/^[A-Za-z0-9_-]{1,100}$/u.test(playlistId)) return { playlistId };
  }
  throw new Error('Music view is invalid.');
}

/** Build one bounded catalog request from browser-owned view and filter state. */
export function musicCatalogParameters({ view, page, q, artist, album, genre }) {
  if (!Number.isSafeInteger(page) || page < 0) throw new Error('Music page is invalid.');
  return {
    q, artist, album, genre, page, size: 50, ...musicViewFilter(view),
  };
}

/** Render accessible page controls from trusted, validated catalog metadata. */
export function musicPaginationMarkup({ page, totalPages }) {
  const pages = musicPageNumbers(page, totalPages);
  if (pages.length <= 1) return '';
  const previous = Math.max(0, page - 1);
  const next = Math.min(totalPages - 1, page + 1);
  const parts = [`<button type="button" data-page="${previous}"${page === 0 ? ' disabled' : ''}>Previous</button>`];
  pages.forEach((value, index) => {
    if (index > 0 && value - pages[index - 1] > 1) {
      parts.push('<span class="music-page-gap" aria-hidden="true">…</span>');
    }
    parts.push(`<button type="button" data-page="${value}"${value === page ? ' aria-current="page"' : ''}>${value + 1}</button>`);
  });
  parts.push(`<button type="button" data-page="${next}"${page === totalPages - 1 ? ' disabled' : ''}>Next</button>`);
  return parts.join('');
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

function musicFacets(value) {
  return Object.freeze({
    artists: strings(value.artists),
    albums: strings(value.albums),
    genres: strings(value.genres),
    years: Object.freeze(Array.isArray(value.years)
      ? value.years.filter(Number.isSafeInteger).slice(0, 500) : []),
  });
}
