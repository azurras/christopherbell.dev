import { API } from './lib/api.js';
import {
  formatMusicDuration,
  musicCatalog,
  musicCatalogParameters,
  musicPaginationMarkup,
  musicPlaylists,
  musicQueue,
  musicQueueMarkup,
  musicTrack,
  musicTrackMarkup,
} from './lib/music.js';
import { playMusicRadio, playMusicTrack } from './lib/site-media-loader.js';
import { fetchJson, loginRedirectUrl, sanitize } from './lib/util.js';

const elements = Object.freeze({
  access: document.getElementById('music-access'),
  library: document.getElementById('music-library'),
  results: document.getElementById('music-results'),
  search: document.getElementById('music-search'),
  query: document.getElementById('music-search-query'),
  radio: document.getElementById('music-radio'),
  artist: document.getElementById('music-artist-filter'),
  album: document.getElementById('music-album-filter'),
  genre: document.getElementById('music-genre-filter'),
  count: document.getElementById('music-count'),
  pagination: document.getElementById('music-pagination'),
  queue: document.getElementById('music-queue'),
  queueCount: document.getElementById('music-queue-count'),
  playlists: document.getElementById('music-playlists'),
  newPlaylist: document.getElementById('music-new-playlist'),
  playlistDialog: document.getElementById('music-playlist-dialog'),
  playlistForm: document.getElementById('music-playlist-form'),
  metadataDialog: document.getElementById('music-metadata-dialog'),
  metadataForm: document.getElementById('music-metadata-form'),
  toast: document.getElementById('music-toast'),
});

const state = {
  canManage: false,
  catalog: {
    tracks: [], facets: { artists: [], albums: [], genres: [], years: [] },
    page: 0, size: 50, totalTracks: 0, totalPages: 0,
  },
  queue: { version: 0, items: [] },
  playlists: [],
  history: [],
  view: 'all',
};
let catalogRequestController = null;

function deniedMarkup(status) {
  if (!status?.authenticated) {
    return `<h2>Sign in to Music</h2><p>Music is available to authorized listeners.</p>
      <a class="btn btn-warning" href="${sanitize(loginRedirectUrl('/music'))}">Sign in</a>`;
  }
  return '<h2>Music access required</h2><p>Your account does not currently have Music access. This attempt was recorded for an administrator.</p>';
}

async function loadWorkspace() {
  const [catalogResponse, queueResponse, playlistsResponse, historyResponse] = await Promise.all([
    fetchJson(API.music.catalog(), { redirectOnUnauthorized: false, cache: 'no-store' }),
    fetchJson(API.music.queue, { redirectOnUnauthorized: false, cache: 'no-store' }),
    fetchJson(API.music.library.playlists, { redirectOnUnauthorized: false, cache: 'no-store' }),
    fetchJson(API.music.library.history(50), { redirectOnUnauthorized: false, cache: 'no-store' }),
  ]);
  state.catalog = musicCatalog(catalogResponse);
  state.queue = musicQueue(queueResponse);
  state.playlists = musicPlaylists(playlistsResponse);
  state.history = Array.isArray(historyResponse) ? historyResponse.slice(0, 100) : [];
  renderWorkspace();
}

async function loadCatalog(page = 0) {
  catalogRequestController?.abort();
  const requestController = new AbortController();
  const requestedView = state.view;
  catalogRequestController = requestController;
  elements.results.innerHTML = '<p class="music-empty">Searching the library…</p>';
  elements.pagination.hidden = true;
  try {
    const response = await fetchJson(API.music.catalog(musicCatalogParameters({
      view: requestedView,
      page,
      q: String(elements.query?.value || '').trim(),
      artist: elements.artist?.value || '',
      album: elements.album?.value || '',
      genre: elements.genre?.value || '',
    })), {
      redirectOnUnauthorized: false,
      cache: 'no-store',
      signal: requestController.signal,
    });
    if (catalogRequestController !== requestController || state.view !== requestedView) return;
    state.catalog = musicCatalog(response);
    renderWorkspace();
  } catch (error) {
    if (error?.name !== 'AbortError') throw error;
  } finally {
    if (catalogRequestController === requestController) catalogRequestController = null;
  }
}

function renderWorkspace() {
  renderFilters();
  renderPlaylists();
  renderQueue();
  renderResults();
  renderPagination();
}

function renderFilters() {
  fillSelect(elements.artist, 'All artists', state.catalog.facets.artists);
  fillSelect(elements.album, 'All albums', state.catalog.facets.albums);
  fillSelect(elements.genre, 'All genres', state.catalog.facets.genres);
}

function fillSelect(select, label, values) {
  if (!select) return;
  const selected = select.value;
  select.replaceChildren(new Option(label, ''), ...values.map(value => new Option(value, value)));
  if (values.includes(selected)) select.value = selected;
}

function renderResults() {
  document.querySelectorAll('[data-view]').forEach(button => {
    button.classList.toggle('is-active', button.dataset.view === state.view);
  });
  if (state.view === 'history') {
    elements.count.textContent = `${state.history.length} recent plays`;
    elements.results.innerHTML = historyMarkup();
    return;
  }
  const tracks = state.catalog.tracks;
  const count = state.catalog.totalTracks.toLocaleString();
  const page = state.catalog.totalPages > 0
    ? ` · Page ${state.catalog.page + 1} of ${state.catalog.totalPages}` : '';
  elements.count.textContent = `${count} track${state.catalog.totalTracks === 1 ? '' : 's'}${page}`;
  elements.results.innerHTML = tracks.length
    ? tracks.map(track => musicTrackMarkup(track, {
      canManage: state.canManage,
      playlists: state.playlists,
    })).join('')
    : '<p class="music-empty">No tracks matched this view.</p>';
}

function renderPagination() {
  if (state.view === 'history') {
    elements.pagination.replaceChildren();
    elements.pagination.hidden = true;
    return;
  }
  const markup = musicPaginationMarkup(state.catalog);
  elements.pagination.innerHTML = markup;
  elements.pagination.hidden = markup.length === 0;
}

function renderQueue() {
  elements.queueCount.textContent = String(state.queue.items.length);
  elements.queue.innerHTML = musicQueueMarkup(state.queue, { canManage: state.canManage });
}

function renderPlaylists() {
  elements.playlists.innerHTML = state.playlists.length
    ? state.playlists.map(item => `<button type="button" data-view="playlist:${sanitize(item.id)}">
      ${sanitize(item.name)} <small>${item.trackIds.length}</small></button>`).join('')
    : '<p class="music-empty">No playlists yet.</p>';
}

function historyMarkup() {
  if (!state.history.length) return '<p class="music-empty">The radio has no history yet.</p>';
  const byId = new Map(state.catalog.tracks.map(track => [track.id, track]));
  return state.history.map(event => {
    const track = byId.get(event?.trackId);
    const title = track?.title || 'Unavailable track';
    const detail = [event?.artist || track?.artist, event?.source].filter(Boolean).join(' · ');
    return `<article class="music-track"><span></span><div class="music-track-art"><span>↺</span></div>
      <div class="music-track-copy"><strong>${sanitize(title)}</strong><span>${sanitize(detail)}</span></div>
      <time>${sanitize(new Date(event?.occurredAt).toLocaleString())}</time><span></span></article>`;
  }).join('');
}

async function trackAction(action, track) {
  if (action === 'play') {
    await playMusicTrack(track);
    return;
  }
  if (!state.canManage) return;
  if (action === 'favorite' || action === 'exclude') {
    const updated = await fetchJson(API.music.library.preferences(track.id), {
      method: 'PATCH',
      body: JSON.stringify({
        expectedFavorite: track.favorite,
        expectedExcludedFromRadio: track.excludedFromRadio,
        favorite: action === 'favorite' ? !track.favorite : track.favorite,
        excludedFromRadio: action === 'exclude'
          ? !track.excludedFromRadio : track.excludedFromRadio,
      }),
    });
    if (action === 'favorite' && state.view === 'favorites') {
      await loadCatalog(state.catalog.page);
    } else {
      replaceTrack(updated);
    }
  } else if (action === 'queue') {
    state.queue = musicQueue(await fetchJson(API.music.queue, {
      method: 'POST', body: JSON.stringify({ trackId: track.id, expectedVersion: state.queue.version }),
    }));
    renderQueue();
  } else if (action === 'edit') {
    openMetadata(track);
  }
}

function replaceTrack(updated) {
  const validated = musicTrack(updated);
  state.catalog = { ...state.catalog, tracks: state.catalog.tracks.map(
    track => track.id === validated.id ? validated : track) };
  renderResults();
}

async function addToPlaylist(playlistId, track) {
  const playlist = state.playlists.find(item => item.id === playlistId);
  if (!playlist || playlist.trackIds.includes(track.id)) return;
  const updated = await fetchJson(API.music.library.playlist(playlist.id), {
    method: 'PUT',
    body: JSON.stringify({
      expectedVersion: playlist.version,
      name: playlist.name,
      trackIds: [...playlist.trackIds, track.id],
    }),
  });
  state.playlists = musicPlaylists(state.playlists.map(
    item => item.id === updated.id ? updated : item));
  renderWorkspace();
  showToast(`Added “${track.title}” to ${playlist.name}.`);
}

function openMetadata(track) {
  document.getElementById('music-edit-track-id').value = track.id;
  document.getElementById('music-edit-title').value = track.title || '';
  document.getElementById('music-edit-artist').value = track.artist || '';
  document.getElementById('music-edit-album-artist').value = track.albumArtist || '';
  document.getElementById('music-edit-album').value = track.album || '';
  document.getElementById('music-edit-track').value = track.trackNumber || '';
  document.getElementById('music-edit-disc').value = track.discNumber || '';
  document.getElementById('music-edit-genre').value = track.genre || '';
  document.getElementById('music-edit-year').value = track.year || '';
  document.getElementById('music-edit-artwork').value = '';
  document.getElementById('music-edit-remove-artwork').checked = false;
  document.getElementById('music-edit-status').textContent = '';
  elements.metadataDialog.showModal();
}

async function saveMetadata() {
  const id = document.getElementById('music-edit-track-id').value;
  const track = state.catalog.tracks.find(item => item.id === id);
  if (!track) return;
  const artworkFile = document.getElementById('music-edit-artwork').files?.[0];
  if (artworkFile?.size > 5 * 1024 * 1024) throw new Error('Artwork must be 5 MB or smaller.');
  const artworkDataUrl = artworkFile ? await fileDataUrl(artworkFile) : null;
  const result = await fetchJson(API.music.metadata(id), {
    method: 'PATCH',
    body: JSON.stringify({
      expectedObservedToken: track.observedToken,
      title: input('music-edit-title'), artist: input('music-edit-artist'),
      albumArtist: input('music-edit-album-artist'), album: input('music-edit-album'),
      trackNumber: integer('music-edit-track'), discNumber: integer('music-edit-disc'),
      genre: input('music-edit-genre'), year: integer('music-edit-year'), artworkDataUrl,
      removeArtwork: document.getElementById('music-edit-remove-artwork').checked,
    }),
  });
  replaceTrack(result.track);
  elements.metadataDialog.close();
  showToast('Track details saved.', {
    label: 'Undo', action: () => undoMetadata(result.editId, result.observedToken),
  });
}

async function undoMetadata(editId, observedToken) {
  const result = await fetchJson(API.music.metadataUndo(editId), {
    method: 'POST', body: JSON.stringify({ expectedObservedToken: observedToken }),
  });
  replaceTrack(result.track);
  showToast('Metadata edit undone.');
}

function showToast(message, option = null) {
  elements.toast.replaceChildren(document.createTextNode(message));
  if (option) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = option.label;
    button.addEventListener('click', () => void option.action().catch(showError), { once: true });
    elements.toast.append(button);
  }
  elements.toast.hidden = false;
  window.setTimeout(() => { elements.toast.hidden = true; }, 8_000);
}

function showError(error) {
  showToast(error?.status === 409 ? 'Music changed. Refresh and try again.' : error?.message || 'Music request failed.');
}

function input(id) { return String(document.getElementById(id)?.value || '').trim() || null; }
function integer(id) { const value = input(id); return value === null ? null : Number(value); }
function fileDataUrl(file) { return new Promise((resolve, reject) => {
  const reader = new FileReader();
  reader.onload = () => resolve(String(reader.result));
  reader.onerror = () => reject(new Error('Artwork could not be read.'));
  reader.readAsDataURL(file);
}); }

elements.search?.addEventListener('submit', event => {
  event.preventDefault();
  void loadCatalog(0).catch(showError);
});
for (const filter of [elements.artist, elements.album, elements.genre]) {
  filter?.addEventListener('change', () => void loadCatalog(0).catch(showError));
}
elements.radio?.addEventListener('click', async () => {
  elements.radio.disabled = true;
  try {
    const response = await playMusicRadio();
    elements.radio.querySelector('span:last-child').textContent =
      response?.status === 'EMPTY' ? 'Radio is empty' : 'Listening live';
  } catch (error) {
    showError(error);
  } finally {
    elements.radio.disabled = false;
  }
});
elements.library?.addEventListener('click', event => {
  const requestedPage = Number(event.target.closest('[data-page]')?.dataset.page);
  if (Number.isSafeInteger(requestedPage) && requestedPage >= 0) {
    void loadCatalog(requestedPage).catch(showError);
    return;
  }
  const view = event.target.closest('[data-view]')?.dataset.view;
  if (view) {
    state.view = view;
    if (view === 'history') renderWorkspace();
    else void loadCatalog(0).catch(showError);
    return;
  }
  const action = event.target.closest('[data-action]')?.dataset.action;
  const row = event.target.closest('[data-track-id]');
  const track = state.catalog.tracks.find(item => item.id === row?.dataset.trackId);
  if (action && track) void trackAction(action, track).catch(showError);
  if (action === 'remove-queue') {
    const queueId = event.target.closest('[data-queue-id]')?.dataset.queueId;
    void fetchJson(`${API.music.queueItem(queueId)}?expectedVersion=${state.queue.version}`, {
      method: 'DELETE',
    }).then(response => { state.queue = musicQueue(response); renderQueue(); }).catch(showError);
  }
});
elements.library?.addEventListener('change', event => {
  if (event.target.dataset.action !== 'playlist' || !event.target.value) return;
  const id = event.target.closest('[data-track-id]')?.dataset.trackId;
  const track = state.catalog.tracks.find(item => item.id === id);
  if (track) void addToPlaylist(event.target.value, track).catch(showError);
  event.target.value = '';
});
elements.newPlaylist?.addEventListener('click', () => elements.playlistDialog.showModal());
elements.playlistForm?.addEventListener('submit', event => {
  if (event.submitter?.value === 'cancel') return;
  event.preventDefault();
  const name = input('music-playlist-name');
  void fetchJson(API.music.library.playlists, {
    method: 'POST', body: JSON.stringify({ name, trackIds: [] }),
  }).then(created => {
    state.playlists = musicPlaylists([...state.playlists, created]);
    elements.playlistDialog.close();
    elements.playlistForm.reset();
    renderWorkspace();
  }).catch(showError);
});
elements.metadataForm?.addEventListener('submit', event => {
  if (event.submitter?.value === 'cancel') return;
  event.preventDefault();
  document.getElementById('music-edit-status').textContent = 'Saving…';
  void saveMetadata().catch(error => {
    document.getElementById('music-edit-status').textContent = error?.message || 'Save failed.';
  });
});

async function initialize() {
  try {
    const status = await fetchJson(API.music.access, { redirectOnUnauthorized: false, cache: 'no-store' });
    if (!status?.allowed) { elements.access.innerHTML = deniedMarkup(status); return; }
    state.canManage = status.canManage === true;
    elements.access.classList.add('d-none');
    elements.library.classList.remove('d-none');
    elements.radio.disabled = false;
    elements.newPlaylist.hidden = !state.canManage;
    await loadWorkspace();
  } catch (error) {
    elements.access.innerHTML = '<h2>Music is temporarily unavailable</h2><p>Please try again shortly.</p>';
  }
}

void initialize();
