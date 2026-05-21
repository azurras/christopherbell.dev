import { API } from './lib/api.js';
import { authHeaders, fetchJson, getAuthClaims, linkMentions, loginRedirectUrl, sanitize } from './lib/util.js';

const mount = document.getElementById('whats-for-lunch');
const LOCATION_OPTIONS = {
  enableHighAccuracy: false,
  maximumAge: 5 * 60 * 1000,
  timeout: 10000,
};
const DEFAULT_RADIUS_MILES = 15;
const RADIUS_OPTIONS = [1, 5, 10, 15, 20];
const RATING_OPTIONS = [1, 2, 3, 4, 5];
const SESSION_POLL_INTERVAL_MS = 5000;
const ANONYMOUS_SESSION_KEY = 'cbellWflAnonymousSession';
const MEMBER_SESSION_KEY_PREFIX = 'cbellWflMemberSession';
const CUISINE_FILTERS = [
  { label: 'Mexican', value: 'mexican' },
  { label: 'BBQ', value: 'barbecue' },
  { label: 'Pizza', value: 'pizza' },
  { label: 'Burgers', value: 'burger' },
  { label: 'Asian', value: 'asian' },
  { label: 'Chinese', value: 'chinese' },
  { label: 'Japanese', value: 'japanese' },
  { label: 'Thai', value: 'thai' },
  { label: 'Indian', value: 'indian' },
  { label: 'Mediterranean', value: 'mediterranean' },
  { label: 'Italian', value: 'italian' },
  { label: 'American', value: 'american' },
  { label: 'Coffee', value: 'coffee' },
  { label: 'Vegan', value: 'vegan' },
  { label: 'Vegetarian', value: 'vegetarian' },
  { label: 'Seafood', value: 'seafood' },
];
let isAdmin = false;
let isLoggedIn = false;
let currentLocation = null;
let currentZipCode = '';
let selectedCuisines = new Set();
let selectedRadiusMiles = DEFAULT_RADIUS_MILES;
let activeControlPanel = 'filters';
let currentPicks = [];
let activeSession = null;
let visibleRatingControls = new Set();
let sessionPollId = null;
let sessionPollInFlight = false;

function wflSecondaryNav(active = 'picks') {
  const items = [
    { key: 'picks', href: '/wfl', label: 'Picks' },
    { key: 'top-rated', href: '/wfl/top-rated', label: 'Top 10 Rated' },
    { key: 'favorites', href: '/wfl/favorites', label: 'Favorites' },
  ];
  return `
    <nav class="wfl-secondary-nav" aria-label="What's For Lunch navigation">
      ${items.map((item) => `
        <a class="${active === item.key ? 'active' : ''}" href="${item.href}">${item.label}</a>
      `).join('')}
    </nav>
  `;
}

function memberSessionKey() {
  const claims = getAuthClaims();
  const subject = String(claims?.sub || 'anonymous');
  return `${MEMBER_SESSION_KEY_PREFIX}:${subject}`;
}

function getStoredMemberSessionId() {
  return localStorage.getItem(memberSessionKey()) || '';
}

function storeMemberSessionId(sessionId) {
  if (sessionId) {
    localStorage.setItem(memberSessionKey(), sessionId);
  }
}

function clearStoredMemberSession() {
  localStorage.removeItem(memberSessionKey());
}

function getStoredAnonymousSession() {
  try {
    const stored = JSON.parse(localStorage.getItem(ANONYMOUS_SESSION_KEY) || '{}');
    const restaurants = Array.isArray(stored.restaurants) ? stored.restaurants : [];
    if (restaurants.length === 0) return null;
    return {
      restaurants,
      location: hasCoordinate(stored.location?.latitude) && hasCoordinate(stored.location?.longitude)
        ? stored.location
        : null,
      zipCode: normalizeZipCode(stored.zipCode),
    };
  } catch (_) {
    localStorage.removeItem(ANONYMOUS_SESSION_KEY);
    return null;
  }
}

function storeAnonymousSession(restaurants) {
  if (!Array.isArray(restaurants) || restaurants.length === 0) return;
  localStorage.setItem(ANONYMOUS_SESSION_KEY, JSON.stringify({
    restaurants,
    location: currentLocation,
    zipCode: currentZipCode,
    createdOn: new Date().toISOString(),
  }));
}

function clearStoredAnonymousSession() {
  localStorage.removeItem(ANONYMOUS_SESSION_KEY);
}

function addressLine(address = {}) {
  return [address.street1, address.city, address.state, address.postalCode]
    .filter(Boolean)
    .join(', ');
}

function hasCoordinate(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function normalizeZipCode(value) {
  const zipCode = String(value || '').trim();
  if (/^\d{5}$/.test(zipCode)) return zipCode;
  if (/^\d{5}-\d{4}$/.test(zipCode)) return zipCode.slice(0, 5);
  return '';
}

function locationControlsMarkup() {
  return `
    <div class="lunch-location-controls">
      <form class="lunch-zip-form">
        <label class="visually-hidden" for="lunch-zip-code">ZIP code</label>
        <input id="lunch-zip-code" class="form-control form-control-sm lunch-zip-input" inputmode="numeric" autocomplete="postal-code" placeholder="ZIP code" value="${sanitize(currentZipCode)}">
        <button type="submit" class="btn btn-outline-primary btn-sm">Use ZIP</button>
      </form>
      <button type="button" class="btn btn-outline-secondary btn-sm lunch-location-request">Use my location</button>
    </div>
  `;
}

function locationPanelMarkup() {
  const currentOrigin = currentZipCode
    ? `Using ZIP ${currentZipCode}.`
    : currentLocation
      ? 'Using your browser location.'
      : 'Choose a ZIP code or browser location before picking lunch.';
  return `
    <section class="lunch-control-panel" aria-label="Lunch location">
      <div class="lunch-control-heading">
        <h2>Location</h2>
        <p>${sanitize(currentOrigin)}</p>
      </div>
      ${locationControlsMarkup()}
    </section>
  `;
}

function directionsUrl(restaurant) {
  if (!restaurant) return '';

  const params = new URLSearchParams({ api: '1', travelmode: 'driving' });
  if (currentLocation) {
    params.set('origin', `${currentLocation.latitude},${currentLocation.longitude}`);
  }
  const address = restaurant.address || {};
  if (hasCoordinate(address.latitude) && hasCoordinate(address.longitude)) {
    params.set('destination', `${address.latitude},${address.longitude}`);
  } else {
    const destination = [restaurant.name, addressLine(address)]
      .filter(Boolean)
      .join(', ');
    if (!destination) return '';
    params.set('destination', destination);
  }

  return `https://www.google.com/maps/dir/?${params}`;
}

function restaurantCard(restaurant, index) {
  const id = restaurant.id || '';
  const restaurantHref = id ? `/wfl/restaurants/${encodeURIComponent(id)}` : '';
  const address = addressLine(restaurant.address);
  const directions = directionsUrl(restaurant);
  const sessionVoters = activeSession?.votesByRestaurant?.[id] || [];
  const myVote = activeSession?.myVoteRestaurantId === id;
  const cuisine = restaurant.cuisine
    ? `<span class="lunch-cuisine">${sanitize(formatCuisine(restaurant.cuisine))}</span>`
    : '';
  const myRating = Number.parseInt(String(restaurant.myRating ?? 0), 10) || 0;
  const favoriteButton = isLoggedIn && id
    ? `<button type="button" class="btn ${restaurant.myFavorite ? 'btn-success' : 'btn-outline-success'} btn-sm lunch-favorite-toggle" data-restaurant-id="${sanitize(id)}" aria-pressed="${restaurant.myFavorite ? 'true' : 'false'}">
        <span aria-hidden="true">&hearts;</span> ${restaurant.myFavorite ? 'Favorited' : 'Favorite'}
      </button>`
    : '';
  const ratingSummary = ratingSummaryMarkup(restaurant);
  const ratingControls = isLoggedIn && id
    ? `<div class="lunch-rating-shell">
        <button type="button" class="btn btn-outline-secondary btn-sm lunch-rating-toggle" data-restaurant-id="${sanitize(id)}" aria-expanded="${visibleRatingControls.has(id)}">
          Rate
        </button>
        <div class="lunch-rating-control ${visibleRatingControls.has(id) ? '' : 'd-none'}" aria-label="Rate ${sanitize(restaurant.name || 'restaurant')}">
          ${RATING_OPTIONS.map((rating) => `
            <button type="button" class="lunch-rating-button ${myRating === rating ? 'active' : ''}" data-restaurant-id="${sanitize(id)}" data-rating="${rating}" aria-label="Rate ${rating} out of 5">${rating}</button>
          `).join('')}
        </div>
      </div>`
    : '';
  const website = restaurant.website
    ? `<a class="btn btn-outline-primary btn-sm" href="${sanitize(restaurant.website)}" target="_blank" rel="noopener">Website</a>`
    : '';
  const phone = restaurant.phoneNumber
    ? `<a class="btn btn-outline-secondary btn-sm" href="tel:${sanitize(restaurant.phoneNumber)}">${sanitize(restaurant.phoneNumber)}</a>`
    : '';
  const directionsButton = directions
    ? `<a class="btn btn-primary btn-sm" href="${sanitize(directions)}" target="_blank" rel="noopener">Directions</a>`
    : '';
  const deleteButton = isAdmin && id
    ? `<button type="button" class="btn btn-outline-danger btn-sm lunch-pick-delete" data-restaurant-id="${sanitize(id)}">Delete</button>`
    : '';
  const voteButton = activeSession && id
    ? `<button type="button" class="btn ${myVote ? 'btn-success' : 'btn-outline-success'} btn-sm lunch-session-vote" data-restaurant-id="${sanitize(id)}">${myVote ? 'Voted' : 'Vote'}</button>`
    : '';
  const voterText = activeSession
    ? `<p class="lunch-session-voters">${sessionVoters.length > 0
        ? `Votes: ${linkMentions(sessionVoters.map((username) => `@${username}`).join(', '))}`
        : 'No votes yet'}</p>`
    : '';

  return `
    <article class="lunch-pick" ${restaurantHref ? `data-restaurant-href="${sanitize(restaurantHref)}"` : ''}>
      <div class="lunch-pick-rank">${index + 1}</div>
      <div class="lunch-pick-body">
        <div class="lunch-pick-header">
          <h2>${restaurantHref
            ? `<a href="${sanitize(restaurantHref)}">${sanitize(restaurant.name || 'Lunch spot')}</a>`
            : sanitize(restaurant.name || 'Lunch spot')}</h2>
          ${deleteButton}
        </div>
        ${cuisine}
        ${address ? `<p>${sanitize(address)}</p>` : ''}
        ${ratingSummary}
        ${ratingControls}
        ${voterText}
        <div class="lunch-pick-actions">${favoriteButton}${voteButton}${directionsButton}${website}${phone}</div>
      </div>
    </article>
  `;
}

function ratingSummaryMarkup(restaurant) {
  const ratingSum = Number.parseInt(String(restaurant.ratingSum ?? 0), 10) || 0;
  const ratingCount = Number.parseInt(String(restaurant.ratingCount ?? 0), 10) || 0;
  const displayedRating = ratingCount > 0 ? Math.round(ratingSum / ratingCount) : 0;
  const overall = ratingCount > 0 ? `${displayedRating}/5` : 'No Ratings';
  const myRating = Number.parseInt(String(restaurant.myRating ?? 0), 10) || 0;
  if (isLoggedIn) {
    return `
      <div class="lunch-rating-summary">
        <p>Overall rating: ${overall}</p>
        <p>Your rating: ${myRating > 0 ? `${myRating}/5` : 'Not rated'}</p>
      </div>
    `;
  }
  return `<p class="lunch-rating-summary">Rating: ${overall}</p>`;
}

function sessionMarkup() {
  if (!isLoggedIn) {
    const loginHref = loginRedirectUrl();
    return `
      <section class="lunch-session-panel" aria-label="Shared lunch session">
        <div>
          <h2>Vote with friends</h2>
          <p>Sign in to invite other members and vote on the same three lunch picks.</p>
        </div>
        <a class="btn btn-outline-primary btn-sm" href="${sanitize(loginHref)}">Sign in</a>
      </section>
    `;
  }

  if (activeSession) {
    const shareUrl = `${window.location.origin}/wfl?session=${encodeURIComponent(activeSession.id)}`;
    const participants = Array.isArray(activeSession.participantUsernames)
      ? activeSession.participantUsernames.map((username) => `@${username}`).join(', ')
      : '';
    return `
      <section class="lunch-session-panel" aria-label="Shared lunch session">
        <div>
          <h2>Shared lunch session</h2>
          <p>${linkMentions(participants || 'Session members')} can vote on these same three picks.</p>
          <input class="form-control form-control-sm lunch-session-link" value="${sanitize(shareUrl)}" readonly aria-label="Session link">
        </div>
        <button type="button" class="btn btn-outline-primary btn-sm lunch-session-copy">Copy link</button>
      </section>
    `;
  }

  return `
    <section class="lunch-session-panel" aria-label="Start shared lunch session">
      <div>
        <h2>Vote with friends</h2>
        <p>Start a shareable session link for these three picks.</p>
      </div>
      <div class="lunch-session-tools">
        <p>Optionally invite members now, or share the session link after it starts.</p>
        <input class="form-control form-control-sm lunch-session-invitees" placeholder="username, anotheruser" aria-label="Invite usernames">
        <div class="lunch-session-actions">
          <button type="button" class="btn btn-primary btn-sm lunch-session-create" ${currentPicks.length === 3 ? '' : 'disabled'}>Create share link</button>
        </div>
        <p class="lunch-session-status" aria-live="polite"></p>
      </div>
    </section>
  `;
}

function filtersMarkup() {
  const saveButton = isLoggedIn
    ? '<button type="button" class="btn btn-outline-primary btn-sm lunch-filter-save">Save filters</button>'
    : `<a class="btn btn-outline-secondary btn-sm" href="${sanitize(loginRedirectUrl())}">Sign in to save</a>`;
  return `
    <section class="lunch-filters lunch-control-panel" aria-label="Food type filters">
      <div class="lunch-control-heading">
        <h2>Filters</h2>
        <p>Choose distance and food types.</p>
      </div>
      <label class="lunch-radius-control">
        <span>Distance</span>
        <select class="form-select form-select-sm lunch-radius-select" aria-label="Search radius">
          ${RADIUS_OPTIONS.map((radius) => `
            <option value="${radius}" ${selectedRadiusMiles === radius ? 'selected' : ''}>${radius} mile${radius === 1 ? '' : 's'}</option>
          `).join('')}
        </select>
      </label>
      <div class="lunch-filter-options">
        ${CUISINE_FILTERS.map((filter) => `
          <label class="lunch-filter">
            <input type="checkbox" value="${sanitize(filter.value)}" ${selectedCuisines.has(filter.value) ? 'checked' : ''}>
            <span>${sanitize(filter.label)}</span>
          </label>
        `).join('')}
      </div>
      <div class="lunch-filter-actions">
        <button type="button" class="btn btn-primary btn-sm lunch-location-refresh">Apply filters</button>
        <button type="button" class="btn btn-outline-secondary btn-sm lunch-filter-clear">Clear</button>
        ${saveButton}
      </div>
      <p class="lunch-filter-status" aria-live="polite"></p>
    </section>
  `;
}

function controlPanelLabel(key) {
  if (key === 'filters') {
    const count = selectedCuisines.size + (selectedRadiusMiles !== DEFAULT_RADIUS_MILES ? 1 : 0);
    return `Filters${count > 0 ? ` (${count})` : ''}`;
  }
  if (key === 'location') {
    return currentZipCode ? `Location: ${currentZipCode}` : 'Location';
  }
  return 'Lunch with Friends';
}

function controlTabMarkup(key) {
  const active = activeControlPanel === key;
  return `
    <button type="button" class="lunch-tool-tab ${active ? 'active' : ''}" data-panel="${key}" aria-selected="${active}">
      ${sanitize(controlPanelLabel(key))}
    </button>
  `;
}

function controlsMarkup() {
  const panels = {
    filters: filtersMarkup,
    location: locationPanelMarkup,
    session: sessionMarkup,
  };
  const panel = panels[activeControlPanel] || panels.filters;
  return `
    <section class="lunch-controls" aria-label="Lunch controls">
      <nav class="lunch-tools-nav" aria-label="Lunch tools">
        ${['filters', 'location', 'session'].map(controlTabMarkup).join('')}
      </nav>
      ${panel()}
    </section>
  `;
}

function renderControlsOnly() {
  const controls = mount.querySelector('.lunch-controls');
  if (controls) {
    controls.outerHTML = controlsMarkup();
  }
}

function selectedFilterLabel() {
  if (selectedCuisines.size === 0) return '';
  return ` Filtered by ${Array.from(selectedCuisines).join(', ')}.`;
}

function renderPicks(picks) {
  currentPicks = Array.isArray(picks) ? picks : [];
  const suggestionLabel = picks.length === 1 ? 'suggestion' : 'suggestions';
  const toolbarText = activeSession
    ? `Showing ${picks.length} lunch ${suggestionLabel} in this session.`
    : `Showing ${picks.length} ${suggestionLabel} within ${selectedRadiusMiles} mile${selectedRadiusMiles === 1 ? '' : 's'}.${sanitize(selectedFilterLabel())}`;
  mount.innerHTML = `
    ${wflSecondaryNav('picks')}
    <div class="lunch-toolbar">
      <div>
        <p>${toolbarText}</p>
      </div>
      <button type="button" class="btn btn-primary lunch-location-refresh lunch-primary-refresh">Try 3 more</button>
    </div>
    ${controlsMarkup()}
    <div class="lunch-picks">${picks.map(restaurantCard).join('')}</div>
  `;
}

function renderPicksLoading(message = 'Picking lunch...') {
  const loadingMarkup = `
    <div class="lunch-picks lunch-picks-loading" aria-live="polite" aria-busy="true">
      <div class="lunch-loading-state">
        <span class="lunch-loading-wheel" aria-hidden="true"></span>
        <p>${sanitize(message)}</p>
      </div>
    </div>
  `;
  const picksContainer = mount.querySelector('.lunch-picks');
  if (picksContainer) {
    picksContainer.outerHTML = loadingMarkup;
  } else if (mount) {
    mount.insertAdjacentHTML('beforeend', loadingMarkup);
  }
}

function renderEmpty() {
  mount.innerHTML = `
    ${wflSecondaryNav('picks')}
    <div class="lunch-empty">
      <h2>No nearby lunch picks</h2>
      <p>No restaurants matched within ${selectedRadiusMiles} mile${selectedRadiusMiles === 1 ? '' : 's'}.${sanitize(selectedFilterLabel())}</p>
      ${controlsMarkup()}
      <button type="button" class="btn btn-primary lunch-location-refresh lunch-primary-refresh">Try again</button>
    </div>
  `;
}

function renderLocationPrompt(message) {
  activeControlPanel = 'location';
  mount.innerHTML = `
    ${wflSecondaryNav('picks')}
    <div class="lunch-empty">
      <h2>Share your location</h2>
      <p>${sanitize(message || 'Use your browser location or enter a ZIP code to find nearby lunch spots.')}</p>
      ${controlsMarkup()}
    </div>
  `;
}

function renderError(err) {
  mount.innerHTML = `
    ${wflSecondaryNav('picks')}
    <div class="lunch-empty">
      <h2>Could not load lunch picks</h2>
      <p>${sanitize(err.message || 'Please try again later.')}</p>
      ${filtersMarkup()}
      <button type="button" class="btn btn-outline-primary lunch-location-refresh">Try again</button>
    </div>
  `;
}

function sessionRedirectPath(sessionId) {
  return `/wfl?session=${encodeURIComponent(sessionId)}`;
}

function authLink(path, sessionId) {
  return path === '/login'
    ? loginRedirectUrl(sessionRedirectPath(sessionId))
    : `${path}?redirect=${encodeURIComponent(sessionRedirectPath(sessionId))}`;
}

function renderSessionAuthPrompt(sessionId) {
  mount.innerHTML = `
    ${wflSecondaryNav('picks')}
    <div class="lunch-empty">
      <h2>Join this lunch session</h2>
      <p>Log in or create an account to join the shared picks and vote.</p>
      <div class="lunch-pick-actions">
        <a class="btn btn-primary" href="${sanitize(authLink('/login', sessionId))}">Log in</a>
        <a class="btn btn-outline-primary" href="${sanitize(authLink('/signup', sessionId))}">Create account</a>
      </div>
    </div>
  `;
}

function getCurrentLocation() {
  if (!navigator.geolocation) {
    return Promise.reject(new Error('This browser does not support location lookup.'));
  }

  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      (position) => resolve({
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
      }),
      () => reject(new Error('Location permission is needed to find nearby lunch spots.')),
      LOCATION_OPTIONS,
    );
  });
}

async function loadAdminState() {
  const claims = getAuthClaims();
  isLoggedIn = !!claims?.sub;
  const role = String(claims?.role || '');
  if (role) {
    localStorage.setItem('cbellRole', role);
  } else {
    localStorage.removeItem('cbellRole');
  }
  return role === 'ADMIN';
}

async function loadPreferences() {
  if (!isLoggedIn) return;

  try {
    const preferences = await fetchJson(API.whatsForLunch.preferences, {
      headers: authHeaders(),
    });
    selectedCuisines = new Set(Array.isArray(preferences?.cuisines) ? preferences.cuisines : []);
    selectedRadiusMiles = normalizeRadius(preferences?.radiusMiles);
  } catch (_) {
    selectedCuisines = new Set();
    selectedRadiusMiles = DEFAULT_RADIUS_MILES;
  }
}

async function savePreferences() {
  if (!isLoggedIn) return;

  const status = mount.querySelector('.lunch-filter-status');
  const button = mount.querySelector('.lunch-filter-save');
  if (button) button.disabled = true;
  if (status) status.textContent = 'Saving filters...';
  try {
    const preferences = await fetchJson(API.whatsForLunch.preferences, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({
        cuisines: Array.from(selectedCuisines),
        radiusMiles: selectedRadiusMiles,
      }),
    });
    selectedCuisines = new Set(Array.isArray(preferences?.cuisines) ? preferences.cuisines : []);
    selectedRadiusMiles = normalizeRadius(preferences?.radiusMiles);
    if (status) status.textContent = 'Filters saved.';
  } catch (err) {
    if (status) status.textContent = err.message || 'Could not save filters.';
  } finally {
    if (button) button.disabled = false;
  }
}

async function loadNearbyPicks() {
  if (activeSession) {
    await refreshSharedSessionPicks();
    return;
  }
  return loadSoloSession({ forceNew: true });
}

async function fetchNearbyPicks() {
  if (currentZipCode) {
    return fetchJson(API.whatsForLunch.nearbyByZip(
      currentZipCode,
      Array.from(selectedCuisines),
      selectedRadiusMiles,
      false,
    ), {
      headers: authHeaders(),
    });
  }

  if (!currentLocation) {
    currentLocation = await getCurrentLocation();
  }

  return fetchJson(API.whatsForLunch.nearby(
    currentLocation,
    Array.from(selectedCuisines),
    selectedRadiusMiles,
    false,
  ), {
    headers: authHeaders(),
  });
}

async function createSessionFromPicks(picks, invitedUsernames = []) {
  return fetchJson(API.whatsForLunch.sessions, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({
      restaurantIds: picks.map((pick) => pick.id).filter(Boolean),
      invitedUsernames,
    }),
  });
}

async function updateSessionRestaurants(picks) {
  if (!activeSession?.id) return null;
  return fetchJson(API.whatsForLunch.sessionRestaurants(activeSession.id), {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({
      restaurantIds: picks.map((pick) => pick.id).filter(Boolean),
    }),
  });
}

async function loadStoredMemberSession() {
  const storedSessionId = getStoredMemberSessionId();
  if (!storedSessionId) return false;
  try {
    await loadSession(storedSessionId, { join: false, storeSession: true });
    return true;
  } catch (_) {
    clearStoredMemberSession();
    return false;
  }
}

function loadStoredAnonymousSession() {
  const stored = getStoredAnonymousSession();
  if (!stored) return false;
  if (stored.location) currentLocation = stored.location;
  currentZipCode = stored.zipCode || '';
  activeSession = null;
  stopSessionPolling();
  renderPicks(stored.restaurants);
  return true;
}

async function loadSoloSession({ forceNew = false } = {}) {
  if (!mount) return;
  activeSession = null;
  stopSessionPolling();
  if (new URLSearchParams(window.location.search).has('session')) {
    window.history.replaceState({}, '', '/wfl');
  }
  if (forceNew) {
    clearStoredMemberSession();
    clearStoredAnonymousSession();
  } else if (isLoggedIn ? await loadStoredMemberSession() : loadStoredAnonymousSession()) {
    return;
  } else if (!currentLocation && !currentZipCode) {
    renderLocationPrompt();
    return;
  }

  if (currentPicks.length > 0) {
    renderPicksLoading();
  } else {
    mount.innerHTML = '<div class="lunch-empty"><p>Picking lunch...</p></div>';
  }

  try {
    const picks = await fetchNearbyPicks();
    if (!Array.isArray(picks) || picks.length === 0) {
      renderEmpty();
      return;
    }
    if (isLoggedIn && picks.length === 3) {
      try {
        activeSession = await createSessionFromPicks(picks);
        storeMemberSessionId(activeSession.id);
        renderPicks(Array.isArray(activeSession?.restaurants) ? activeSession.restaurants : picks);
        return;
      } catch (_) {
        clearStoredMemberSession();
        activeSession = null;
      }
    } else if (!isLoggedIn) {
      storeAnonymousSession(picks);
    }
    renderPicks(picks);
  } catch (err) {
    if (!currentLocation) {
      renderLocationPrompt(err.message);
      return;
    }
    renderError(err);
  }
}

async function loadSession(sessionId, { join = true, storeSession = false } = {}) {
  if (!mount) return;
  if (!isLoggedIn) {
    renderSessionAuthPrompt(sessionId);
    return;
  }
  mount.innerHTML = '<div class="lunch-empty"><p>Loading lunch session...</p></div>';
  try {
    activeSession = await fetchJson(
      join ? API.whatsForLunch.sessionJoin(sessionId) : API.whatsForLunch.session(sessionId),
      {
        method: join ? 'POST' : 'GET',
        headers: authHeaders(),
      },
    );
    if (storeSession || join) {
      storeMemberSessionId(activeSession.id);
    }
    startSessionPolling();
    renderPicks(Array.isArray(activeSession?.restaurants) ? activeSession.restaurants : []);
  } catch (err) {
    activeSession = null;
    stopSessionPolling();
    throw err;
  }
}

async function loadSharedSession(sessionId) {
  try {
    await loadSession(sessionId, { join: true, storeSession: true });
  } catch (err) {
    renderError(err);
  }
}

async function createSession() {
  const inviteInput = mount.querySelector('.lunch-session-invitees');
  const status = mount.querySelector('.lunch-session-status');
  const button = mount.querySelector('.lunch-session-create');
  const invitedUsernames = String(inviteInput?.value || '')
    .split(/[,\s]+/)
    .map((username) => username.trim())
    .filter(Boolean);
  if (button) button.disabled = true;
  if (status) status.textContent = 'Starting session...';
  try {
    activeSession = await createSessionFromPicks(currentPicks, invitedUsernames);
    storeMemberSessionId(activeSession.id);
    window.history.replaceState({}, '', `/wfl?session=${encodeURIComponent(activeSession.id)}`);
    startSessionPolling();
    renderPicks(Array.isArray(activeSession?.restaurants) ? activeSession.restaurants : currentPicks);
  } catch (err) {
    if (status) status.textContent = err.message || 'Could not start session.';
  } finally {
    if (button) button.disabled = false;
  }
}

async function refreshSharedSessionPicks() {
  if (!activeSession) return;
  const button = mount.querySelector('.lunch-location-refresh');
  if (button) button.disabled = true;
  renderPicksLoading('Finding 3 more for everyone...');
  try {
    const picks = await fetchNearbyPicks();
    if (!Array.isArray(picks) || picks.length !== 3) {
      throw new Error('A shared lunch session needs three nearby picks.');
    }
    activeSession = await updateSessionRestaurants(picks);
    renderPicks(Array.isArray(activeSession?.restaurants) ? activeSession.restaurants : picks);
  } catch (err) {
    mount.insertAdjacentHTML('afterbegin', `
      <div class="alert alert-danger" role="alert">${sanitize(err.message || 'Could not update the shared session.')}</div>
    `);
  } finally {
    if (button) button.disabled = false;
  }
}

function startSessionPolling() {
  if (!activeSession?.id || !isLoggedIn || sessionPollId) return;
  sessionPollId = window.setInterval(refreshActiveSession, SESSION_POLL_INTERVAL_MS);
}

function stopSessionPolling() {
  if (!sessionPollId) return;
  window.clearInterval(sessionPollId);
  sessionPollId = null;
}

function sessionRestaurantIds(session) {
  return Array.isArray(session?.restaurants)
    ? session.restaurants.map((restaurant) => restaurant.id).filter(Boolean).join('|')
    : '';
}

function sessionChanged(latest, current) {
  return latest?.lastUpdatedOn !== current?.lastUpdatedOn
    || sessionRestaurantIds(latest) !== sessionRestaurantIds(current);
}

async function refreshActiveSession() {
  if (!activeSession?.id || sessionPollInFlight) return;
  sessionPollInFlight = true;
  const sessionId = activeSession.id;
  try {
    const latest = await fetchJson(API.whatsForLunch.session(sessionId), {
      headers: authHeaders(),
    });
    if (activeSession?.id === sessionId && sessionChanged(latest, activeSession)) {
      activeSession = latest;
      renderPicks(Array.isArray(activeSession?.restaurants) ? activeSession.restaurants : currentPicks);
    }
  } catch (_) {
    stopSessionPolling();
  } finally {
    sessionPollInFlight = false;
  }
}

async function loadLunchPicks() {
  if (!mount) return;
  isAdmin = await loadAdminState();
  await loadPreferences();
  const sessionId = new URLSearchParams(window.location.search).get('session');
  if (sessionId) {
    await loadSharedSession(sessionId);
    return;
  }
  await loadSoloSession();
}

mount?.addEventListener('click', async (event) => {
  const refreshButton = event.target instanceof Element
    ? event.target.closest('.lunch-location-refresh, .lunch-location-request')
    : null;
  if (refreshButton) {
    if (refreshButton.matches('.lunch-location-request')) {
      currentZipCode = '';
      currentLocation = null;
      activeControlPanel = 'location';
    }
    await loadNearbyPicks();
    return;
  }

  const clearButton = event.target instanceof Element
    ? event.target.closest('.lunch-filter-clear')
    : null;
  if (clearButton) {
    selectedCuisines = new Set();
    selectedRadiusMiles = DEFAULT_RADIUS_MILES;
    await loadNearbyPicks();
    return;
  }

  const toolTab = event.target instanceof Element
    ? event.target.closest('.lunch-tool-tab')
    : null;
  if (toolTab) {
    activeControlPanel = toolTab.dataset.panel || 'filters';
    renderControlsOnly();
    return;
  }

  const saveButton = event.target instanceof Element
    ? event.target.closest('.lunch-filter-save')
    : null;
  if (saveButton) {
    await savePreferences();
    return;
  }

  const createSessionButton = event.target instanceof Element
    ? event.target.closest('.lunch-session-create')
    : null;
  if (createSessionButton) {
    await createSession();
    return;
  }

  const copySessionButton = event.target instanceof Element
    ? event.target.closest('.lunch-session-copy')
    : null;
  if (copySessionButton) {
    const link = mount.querySelector('.lunch-session-link')?.value || window.location.href;
    await navigator.clipboard?.writeText(link);
    copySessionButton.textContent = 'Copied';
    return;
  }

  const voteButton = event.target instanceof Element
    ? event.target.closest('.lunch-session-vote')
    : null;
  if (voteButton) {
    await voteForRestaurant(voteButton.dataset.restaurantId);
    return;
  }

  const ratingButton = event.target instanceof Element
    ? event.target.closest('.lunch-rating-button')
    : null;
  if (ratingButton) {
    await rateRestaurant(ratingButton.dataset.restaurantId, ratingButton.dataset.rating);
    return;
  }

  const favoriteButton = event.target instanceof Element
    ? event.target.closest('.lunch-favorite-toggle')
    : null;
  if (favoriteButton) {
    await toggleFavorite(favoriteButton.dataset.restaurantId);
    return;
  }

  const ratingToggle = event.target instanceof Element
    ? event.target.closest('.lunch-rating-toggle')
    : null;
  if (ratingToggle) {
    const restaurantId = ratingToggle.dataset.restaurantId;
    if (restaurantId && visibleRatingControls.has(restaurantId)) {
      visibleRatingControls.delete(restaurantId);
    } else if (restaurantId) {
      visibleRatingControls.add(restaurantId);
    }
    const ratingControl = ratingToggle.parentElement?.querySelector('.lunch-rating-control');
    if (ratingControl) ratingControl.classList.toggle('d-none', !visibleRatingControls.has(restaurantId));
    ratingToggle.setAttribute('aria-expanded', String(visibleRatingControls.has(restaurantId)));
    return;
  }

  const button = event.target instanceof Element
    ? event.target.closest('.lunch-pick-delete')
    : null;
  if (!button) {
    const card = event.target instanceof Element ? event.target.closest('.lunch-pick') : null;
    const action = event.target instanceof Element ? event.target.closest('a, button, input, select, label') : null;
    const href = card?.dataset.restaurantHref;
    if (href && !action) {
      window.location.href = href;
    }
    return;
  }

  const restaurantId = button.dataset.restaurantId;
  if (!restaurantId) return;

  button.disabled = true;
  button.textContent = 'Deleting...';
  try {
    await fetchJson(API.whatsForLunch.deleteRestaurant(restaurantId), {
      method: 'DELETE',
      headers: authHeaders(),
    });
    await loadNearbyPicks();
  } catch (err) {
    button.disabled = false;
    button.textContent = 'Delete';
    mount.insertAdjacentHTML('afterbegin', `
      <div class="alert alert-danger" role="alert">${sanitize(err.message || 'Could not delete restaurant.')}</div>
    `);
  }
});

mount?.addEventListener('submit', async (event) => {
  const form = event.target instanceof HTMLFormElement
    ? event.target.closest('.lunch-zip-form')
    : null;
  if (!form) return;

  event.preventDefault();
  const input = form.querySelector('.lunch-zip-input');
  const zipCode = normalizeZipCode(input?.value);
  if (!zipCode) {
    if (input instanceof HTMLInputElement) {
      input.setCustomValidity('Enter a valid 5-digit ZIP code.');
      input.reportValidity();
      input.setCustomValidity('');
    }
    return;
  }

  currentZipCode = zipCode;
  currentLocation = null;
  activeControlPanel = 'location';
  await loadNearbyPicks();
});

async function voteForRestaurant(restaurantId) {
  if (!activeSession || !restaurantId) return;
  const button = Array.from(mount.querySelectorAll('.lunch-session-vote'))
    .find((candidate) => candidate.dataset.restaurantId === restaurantId);
  if (button) button.disabled = true;
  try {
    activeSession = await fetchJson(API.whatsForLunch.sessionVote(activeSession.id), {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ restaurantId }),
    });
    renderPicks(Array.isArray(activeSession?.restaurants) ? activeSession.restaurants : currentPicks);
  } catch (err) {
    mount.insertAdjacentHTML('afterbegin', `
      <div class="alert alert-danger" role="alert">${sanitize(err.message || 'Could not save vote.')}</div>
    `);
  } finally {
    if (button) button.disabled = false;
  }
}

async function rateRestaurant(restaurantId, rating) {
  const selectedRating = Number.parseInt(String(rating), 10);
  if (!restaurantId || !RATING_OPTIONS.includes(selectedRating)) return;
  const button = Array.from(mount.querySelectorAll('.lunch-rating-button'))
    .find((candidate) => candidate.dataset.restaurantId === restaurantId
      && Number.parseInt(candidate.dataset.rating || '', 10) === selectedRating);
  if (button) button.disabled = true;
  try {
    const updatedRestaurant = await fetchJson(API.whatsForLunch.rateRestaurant, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ restaurantId, rating: selectedRating }),
    });
    currentPicks = currentPicks.map((restaurant) => restaurant.id === restaurantId ? updatedRestaurant : restaurant);
    if (activeSession?.restaurants) {
      activeSession = {
        ...activeSession,
        restaurants: activeSession.restaurants.map((restaurant) =>
          restaurant.id === restaurantId ? updatedRestaurant : restaurant),
      };
    }
    renderPicks(currentPicks);
  } catch (err) {
    mount.insertAdjacentHTML('afterbegin', `
      <div class="alert alert-danger" role="alert">${sanitize(err.message || 'Could not save rating.')}</div>
    `);
  } finally {
    if (button) button.disabled = false;
  }
}

async function toggleFavorite(restaurantId) {
  if (!restaurantId) return;
  const restaurant = currentPicks.find((pick) => pick.id === restaurantId)
    || activeSession?.restaurants?.find((pick) => pick.id === restaurantId);
  const isFavorite = !!restaurant?.myFavorite;
  const button = Array.from(mount.querySelectorAll('.lunch-favorite-toggle'))
    .find((candidate) => candidate.dataset.restaurantId === restaurantId);
  if (button) button.disabled = true;
  try {
    const updatedRestaurant = await fetchJson(API.whatsForLunch.favoriteRestaurant, {
      method: isFavorite ? 'DELETE' : 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ restaurantId }),
    });
    currentPicks = currentPicks.map((pick) => pick.id === restaurantId ? updatedRestaurant : pick);
    if (activeSession?.restaurants) {
      activeSession = {
        ...activeSession,
        restaurants: activeSession.restaurants.map((pick) =>
          pick.id === restaurantId ? updatedRestaurant : pick),
      };
    }
    renderPicks(currentPicks);
  } catch (err) {
    mount.insertAdjacentHTML('afterbegin', `
      <div class="alert alert-danger" role="alert">${sanitize(err.message || 'Could not update favorite.')}</div>
    `);
  } finally {
    if (button) button.disabled = false;
  }
}

mount?.addEventListener('change', (event) => {
  const radiusSelect = event.target instanceof HTMLSelectElement
    ? event.target.closest('.lunch-radius-select')
    : null;
  if (radiusSelect) {
    selectedRadiusMiles = normalizeRadius(radiusSelect.value);
    return;
  }

  const filterInput = event.target instanceof HTMLInputElement
    ? event.target.closest('.lunch-filter input[type="checkbox"]')
    : null;
  if (!filterInput) return;

  if (filterInput.checked) {
    selectedCuisines.add(filterInput.value);
  } else {
    selectedCuisines.delete(filterInput.value);
  }
});

function normalizeRadius(value) {
  const radius = Number.parseInt(String(value ?? DEFAULT_RADIUS_MILES), 10);
  return RADIUS_OPTIONS.includes(radius) ? radius : DEFAULT_RADIUS_MILES;
}

function formatCuisine(value) {
  return String(value || '')
    .split(/([;,/|])/)
    .map((part) => /^[;,/|]$/.test(part) ? `${part} ` : titleCaseCuisine(part))
    .join('')
    .replace(/\s+/g, ' ')
    .trim();
}

function titleCaseCuisine(value) {
  return value
    .trim()
    .replace(/[_-]+/g, ' ')
    .split(/\s+/)
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
    .join(' ');
}

loadLunchPicks();
