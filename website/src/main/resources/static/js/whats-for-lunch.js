import { API } from './lib/api.js';
import { authHeaders, fetchJson, sanitize } from './lib/util.js';

const mount = document.getElementById('whats-for-lunch');
const LOCATION_OPTIONS = {
  enableHighAccuracy: false,
  maximumAge: 5 * 60 * 1000,
  timeout: 10000,
};
const RADIUS_MILES = 15;
let isAdmin = false;
let currentLocation = null;

function supportNote() {
  return '<p class="lunch-support-note">Currently supports the Austin metro area only.</p>';
}

function addressLine(address = {}) {
  return [address.street1, address.city, address.state, address.postalCode]
    .filter(Boolean)
    .join(', ');
}

function hasCoordinate(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function directionsUrl(restaurant) {
  if (!currentLocation || !restaurant) return '';

  const params = new URLSearchParams({
    api: '1',
    origin: `${currentLocation.latitude},${currentLocation.longitude}`,
    travelmode: 'driving',
  });
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
  const address = addressLine(restaurant.address);
  const directions = directionsUrl(restaurant);
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

  return `
    <article class="lunch-pick">
      <div class="lunch-pick-rank">${index + 1}</div>
      <div class="lunch-pick-body">
        <div class="lunch-pick-header">
          <h2>${sanitize(restaurant.name || 'Lunch spot')}</h2>
          ${deleteButton}
        </div>
        ${address ? `<p>${sanitize(address)}</p>` : ''}
        <div class="lunch-pick-actions">${directionsButton}${website}${phone}</div>
      </div>
    </article>
  `;
}

function renderPicks(picks) {
  const suggestionLabel = picks.length === 1 ? 'suggestion' : 'suggestions';
  mount.innerHTML = `
    <div class="lunch-toolbar">
      <div>
        <p>Showing ${picks.length} ${suggestionLabel} within ${RADIUS_MILES} miles.</p>
        ${supportNote()}
      </div>
      <button type="button" class="btn btn-outline-primary btn-sm lunch-location-refresh">Try 3 more</button>
    </div>
    <div class="lunch-picks">${picks.map(restaurantCard).join('')}</div>
  `;
}

function renderEmpty() {
  mount.innerHTML = `
    <div class="lunch-empty">
      <h2>No nearby lunch picks</h2>
      <p>No restaurants with saved coordinates were found within ${RADIUS_MILES} miles.</p>
      ${supportNote()}
      <button type="button" class="btn btn-outline-primary lunch-location-refresh">Try again</button>
    </div>
  `;
}

function renderLocationPrompt(message) {
  mount.innerHTML = `
    <div class="lunch-empty">
      <h2>Share your location</h2>
      <p>${sanitize(message || 'Location access is needed to find nearby lunch spots.')}</p>
      ${supportNote()}
      <button type="button" class="btn btn-primary lunch-location-request">Use my location</button>
    </div>
  `;
}

function renderError(err) {
  mount.innerHTML = `
    <div class="lunch-empty">
      <h2>Could not load lunch picks</h2>
      <p>${sanitize(err.message || 'Please try again later.')}</p>
      ${supportNote()}
      <button type="button" class="btn btn-outline-primary lunch-location-refresh">Try again</button>
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
  const token = localStorage.getItem('cbellLoginToken');
  if (!token) return false;

  try {
    const resp = await fetch(API.accounts.me, { headers: authHeaders() });
    const data = await resp.json().catch(() => ({}));
    if (!resp.ok) {
      localStorage.removeItem('cbellRole');
      return false;
    }
    const role = data?.payload?.role || '';
    if (role) {
      localStorage.setItem('cbellRole', role);
    }
    return role === 'ADMIN';
  } catch (_) {
    localStorage.removeItem('cbellRole');
    return false;
  }
}

async function loadNearbyPicks() {
  if (!mount) return;
  mount.innerHTML = '<div class="lunch-empty"><p>Picking lunch...</p></div>';

  try {
    if (!currentLocation) {
      currentLocation = await getCurrentLocation();
    }

    const picks = await fetchJson(API.whatsForLunch.nearby(currentLocation), {
      headers: authHeaders(),
    });
    if (!Array.isArray(picks) || picks.length === 0) {
      renderEmpty();
      return;
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

async function loadLunchPicks() {
  if (!mount) return;
  isAdmin = await loadAdminState();
  await loadNearbyPicks();
}

mount?.addEventListener('click', async (event) => {
  const refreshButton = event.target instanceof Element
    ? event.target.closest('.lunch-location-refresh, .lunch-location-request')
    : null;
  if (refreshButton) {
    currentLocation = null;
    await loadNearbyPicks();
    return;
  }

  const button = event.target instanceof Element
    ? event.target.closest('.lunch-pick-delete')
    : null;
  if (!button) return;

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

loadLunchPicks();
