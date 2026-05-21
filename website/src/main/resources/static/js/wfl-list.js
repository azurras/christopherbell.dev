import { API } from './lib/api.js';
import { authHeaders, fetchJson, getAuthClaims, loginRedirectUrl, sanitize } from './lib/util.js';

const mount = document.getElementById('wfl-list');
const mode = mount?.dataset.listMode || 'top-rated';
const title = mount?.dataset.listTitle || 'Restaurants';
const isLoggedIn = !!getAuthClaims()?.sub;
let restaurants = [];

function wflSecondaryNav(active = 'top-rated') {
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

function addressLine(address = {}) {
  return [address.street1, address.city, address.state, address.postalCode]
    .filter(Boolean)
    .join(', ');
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

function restaurantCard(restaurant, index) {
  const id = restaurant.id || '';
  const href = id ? `/wfl/restaurants/${encodeURIComponent(id)}` : '/wfl';
  const address = addressLine(restaurant.address);
  const cuisine = restaurant.cuisine
    ? `<span class="lunch-cuisine">${sanitize(formatCuisine(restaurant.cuisine))}</span>`
    : '';
  const favoriteButton = isLoggedIn && id
    ? `<button type="button" class="btn ${restaurant.myFavorite ? 'btn-success' : 'btn-outline-success'} btn-sm wfl-list-favorite" data-restaurant-id="${sanitize(id)}" aria-pressed="${restaurant.myFavorite ? 'true' : 'false'}">
        <span aria-hidden="true">&hearts;</span> ${restaurant.myFavorite ? 'Favorited' : 'Favorite'}
      </button>`
    : '';
  return `
    <article class="lunch-pick wfl-list-card">
      <div class="lunch-pick-rank">${index + 1}</div>
      <div class="lunch-pick-body">
        <div class="lunch-pick-header">
          <h2><a href="${sanitize(href)}">${sanitize(restaurant.name || 'Restaurant')}</a></h2>
          ${favoriteButton}
        </div>
        ${cuisine}
        ${address ? `<p>${sanitize(address)}</p>` : ''}
        ${ratingSummaryMarkup(restaurant)}
        <div class="lunch-pick-actions">
          <a class="btn btn-outline-primary btn-sm" href="${sanitize(href)}">Details</a>
        </div>
      </div>
    </article>
  `;
}

function renderList() {
  const emptyText = mode === 'favorites'
    ? 'No favorite restaurants yet.'
    : 'No rated restaurants yet.';
  mount.innerHTML = `
    ${wflSecondaryNav(mode)}
    <div class="wfl-list-heading">
      <h2>${sanitize(title)}</h2>
      <p>${mode === 'favorites'
        ? 'Restaurants you have marked as favorites.'
        : 'The highest-rated WFL restaurants with at least one member rating.'}</p>
    </div>
    ${restaurants.length > 0
      ? `<div class="lunch-picks wfl-list">${restaurants.map(restaurantCard).join('')}</div>`
      : `<div class="lunch-empty"><h2>${emptyText}</h2><a class="btn btn-outline-primary" href="/wfl">Back to picks</a></div>`}
  `;
}

function renderLoginPrompt() {
  mount.innerHTML = `
    ${wflSecondaryNav('favorites')}
    <div class="lunch-empty">
      <h2>Sign in to see favorites</h2>
      <p>Your favorite restaurants are saved to your account.</p>
      <a class="btn btn-primary" href="${sanitize(loginRedirectUrl('/wfl/favorites'))}">Log in</a>
    </div>
  `;
}

function renderError(err) {
  mount.innerHTML = `
    ${wflSecondaryNav(mode)}
    <div class="lunch-empty">
      <h2>Could not load restaurants</h2>
      <p>${sanitize(err.message || 'Please try again later.')}</p>
      <button type="button" class="btn btn-outline-primary wfl-list-retry">Try again</button>
    </div>
  `;
}

async function loadRestaurants() {
  if (!mount) return;
  if (mode === 'favorites' && !isLoggedIn) {
    renderLoginPrompt();
    return;
  }

  mount.innerHTML = `
    ${wflSecondaryNav(mode)}
    <div class="lunch-empty"><p>Loading restaurants...</p></div>
  `;

  try {
    restaurants = await fetchJson(
      mode === 'favorites' ? API.whatsForLunch.favorites : API.whatsForLunch.topRated(10),
      { headers: authHeaders() },
    );
    restaurants = Array.isArray(restaurants) ? restaurants : [];
    renderList();
  } catch (err) {
    if (mode === 'favorites' && err.message === 'Authentication required.') {
      renderLoginPrompt();
      return;
    }
    renderError(err);
  }
}

async function toggleFavorite(restaurantId) {
  if (!restaurantId) return;
  const restaurant = restaurants.find((candidate) => candidate.id === restaurantId);
  const isFavorite = !!restaurant?.myFavorite;
  try {
    const updated = await fetchJson(API.whatsForLunch.favoriteRestaurant, {
      method: isFavorite ? 'DELETE' : 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ restaurantId }),
    });
    restaurants = mode === 'favorites' && isFavorite
      ? restaurants.filter((candidate) => candidate.id !== restaurantId)
      : restaurants.map((candidate) => candidate.id === restaurantId ? updated : candidate);
    renderList();
  } catch (err) {
    mount.insertAdjacentHTML('afterbegin', `
      <div class="alert alert-danger" role="alert">${sanitize(err.message || 'Could not update favorite.')}</div>
    `);
  }
}

mount?.addEventListener('click', async (event) => {
  const favoriteButton = event.target instanceof Element
    ? event.target.closest('.wfl-list-favorite')
    : null;
  if (favoriteButton) {
    favoriteButton.disabled = true;
    await toggleFavorite(favoriteButton.dataset.restaurantId);
    favoriteButton.disabled = false;
    return;
  }

  const retryButton = event.target instanceof Element
    ? event.target.closest('.wfl-list-retry')
    : null;
  if (retryButton) {
    await loadRestaurants();
  }
});

loadRestaurants();
