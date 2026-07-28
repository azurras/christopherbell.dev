import { API } from './lib/api.js';
import { fetchJson, loginRedirectUrl, sanitize } from './lib/util.js';

const accessPanel = document.getElementById('music-access');
const library = document.getElementById('music-library');
const results = document.getElementById('music-results');
const search = document.getElementById('music-search');

function deniedMarkup(status) {
  if (!status?.authenticated) {
    return `<h2>Sign in to Music</h2><p>Music is available to approved listeners.</p>
      <a class="btn btn-warning" href="${sanitize(loginRedirectUrl('/music'))}">Sign in</a>`;
  }
  return '<h2>Music access required</h2><p>Your account does not currently have Music access. An administrator can grant MUSIC_READ.</p>';
}

function trackMarkup(track) {
  const details = [track.artist, track.album].filter(Boolean).map(sanitize).join(' · ');
  return `<article class="queue-card" data-music-track-id="${sanitize(track.id)}">
    <div class="queue-card-main"><strong>${sanitize(track.title || 'Unknown track')}</strong>
      <span>${details || 'Unknown artist'}</span></div>
    <div class="queue-card-meta"><span>${Math.round(Number(track.durationSeconds) || 0)} seconds</span></div>
  </article>`;
}

async function loadCatalog(query = '') {
  results.innerHTML = '<div class="empty-state">Loading music…</div>';
  const response = await fetchJson(API.music.catalog({ q: query }), {
    redirectOnUnauthorized: false,
    cache: 'no-store',
  });
  results.innerHTML = Array.isArray(response?.tracks) && response.tracks.length
    ? response.tracks.map(trackMarkup).join('')
    : '<div class="empty-state">No music matched.</div>';
}

async function initialize() {
  try {
    const status = await fetchJson(API.music.access, {
      redirectOnUnauthorized: false,
      cache: 'no-store',
    });
    if (!status?.allowed) {
      accessPanel.innerHTML = deniedMarkup(status);
      return;
    }
    accessPanel.classList.add('d-none');
    library.classList.remove('d-none');
    await loadCatalog();
  } catch (_) {
    accessPanel.innerHTML = '<h2>Music is temporarily unavailable</h2><p>Please try again shortly.</p>';
  }
}

search?.addEventListener('submit', event => {
  event.preventDefault();
  void loadCatalog(String(document.getElementById('music-search-query')?.value || '').trim());
});

void initialize();
