import { API } from './lib/api.js';
import { authHeaders, fetchJson, getAuthClaims, loginRedirectUrl, sanitize } from './lib/util.js';

const mount = document.getElementById('restaurant-profile');
const title = document.getElementById('restaurantTitle');
const heroText = document.getElementById('restaurantHeroText');
const RATING_OPTIONS = [1, 2, 3, 4, 5];
let currentRestaurant = null;
let isLoggedIn = false;

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

function restaurantIdFromPath() {
  const match = window.location.pathname.match(/\/wfl\/restaurants\/([^/]+)$/);
  return match ? decodeURIComponent(match[1]) : '';
}

function addressLine(address = {}) {
  return [address.street1, address.street2, address.city, address.state, address.postalCode]
    .filter(Boolean)
    .join(', ');
}

function ratingMarkup(restaurant) {
  const count = Number.parseInt(String(restaurant.ratingCount ?? 0), 10) || 0;
  const sum = Number.parseInt(String(restaurant.ratingSum ?? 0), 10) || 0;
  const rating = count > 0 ? Math.round(sum / count) : 0;
  const overall = count > 0 ? `${rating}/5` : 'No Ratings';
  const myRating = Number.parseInt(String(restaurant.myRating ?? 0), 10) || 0;
  if (!isLoggedIn) {
    return `<p class="restaurant-profile-rating">Rating: ${overall}</p>`;
  }
  return `
    <div class="restaurant-profile-rating">
      <p>Overall rating: ${overall}</p>
      <p>Your rating: ${myRating > 0 ? `${myRating}/5` : 'Not rated'}</p>
    </div>
    <div class="lunch-rating-control" aria-label="Rate ${sanitize(restaurant.name || 'restaurant')}">
      ${RATING_OPTIONS.map((value) => `
        <button type="button" class="lunch-rating-button ${myRating === value ? 'active' : ''}" data-rating="${value}" aria-label="Rate ${value} out of 5">${value}</button>
      `).join('')}
    </div>
  `;
}

function mapsUrl(restaurant) {
  const address = restaurant.address || {};
  const destination = address.latitude && address.longitude
    ? `${address.latitude},${address.longitude}`
    : [restaurant.name, addressLine(address)].filter(Boolean).join(', ');
  const params = new URLSearchParams({ api: '1', destination });
  return `https://www.google.com/maps/search/?${params}`;
}

function renderRestaurant(restaurant) {
  currentRestaurant = restaurant;
  const address = addressLine(restaurant.address);
  const favoriteAction = isLoggedIn
    ? `<button type="button" class="btn ${restaurant.myFavorite ? 'btn-success' : 'btn-outline-success'} restaurant-favorite-toggle" aria-pressed="${restaurant.myFavorite ? 'true' : 'false'}">
        <span aria-hidden="true">&hearts;</span> ${restaurant.myFavorite ? 'Favorited' : 'Favorite'}
      </button>`
    : `<a class="btn btn-outline-success" href="${sanitize(loginRedirectUrl())}">Sign in to favorite</a>`;
  if (title) title.textContent = restaurant.name || 'Restaurant';
  if (heroText) heroText.textContent = address || 'Restaurant details from What\'s For Lunch.';
  mount.innerHTML = `
    ${wflSecondaryNav('picks')}
    <article class="restaurant-profile">
      <div>
        <p class="home-kicker mb-2">${sanitize(restaurant.cuisine || 'Restaurant')}</p>
        <h2>${sanitize(restaurant.name || 'Restaurant')}</h2>
        ${ratingMarkup(restaurant)}
        ${address ? `<p>${sanitize(address)}</p>` : ''}
      </div>
      <dl class="restaurant-detail-list">
        <div>
          <dt>Phone</dt>
          <dd>${restaurant.phoneNumber ? `<a href="tel:${sanitize(restaurant.phoneNumber)}">${sanitize(restaurant.phoneNumber)}</a>` : 'Not listed'}</dd>
        </div>
        <div>
          <dt>Website</dt>
          <dd>${restaurant.website ? `<a href="${sanitize(restaurant.website)}" target="_blank" rel="noopener">${sanitize(restaurant.website)}</a>` : 'Not listed'}</dd>
        </div>
        <div>
          <dt>Source type</dt>
          <dd>${sanitize(restaurant.sourceAmenity || 'Not listed')}</dd>
        </div>
      </dl>
      <div class="lunch-pick-actions">
        ${favoriteAction}
        <a class="btn btn-primary" href="${sanitize(mapsUrl(restaurant))}" target="_blank" rel="noopener">Open in Maps</a>
        <a class="btn btn-outline-secondary" href="/wfl">Back to WFL</a>
      </div>
    </article>
  `;
}

async function loadRestaurant() {
  if (!mount) return;
  isLoggedIn = !!getAuthClaims()?.sub;
  const restaurantId = restaurantIdFromPath();
  if (!restaurantId) {
    mount.innerHTML = '<div class="lunch-empty"><h2>Restaurant not found</h2></div>';
    return;
  }
  mount.innerHTML = '<div class="lunch-empty"><p>Loading restaurant...</p></div>';
  try {
    const restaurant = await fetchJson(API.whatsForLunch.restaurant(restaurantId), {
      headers: authHeaders(),
    });
    renderRestaurant(restaurant);
  } catch (err) {
    mount.innerHTML = `
      <div class="lunch-empty">
        <h2>Could not load restaurant</h2>
        <p>${sanitize(err.message || 'Please try again later.')}</p>
        <a class="btn btn-outline-secondary" href="/wfl">Back to WFL</a>
      </div>
    `;
  }
}

async function rateRestaurant(rating) {
  const restaurantId = currentRestaurant?.id;
  const selectedRating = Number.parseInt(String(rating), 10);
  if (!restaurantId || !RATING_OPTIONS.includes(selectedRating)) return;
  try {
    const updatedRestaurant = await fetchJson(API.whatsForLunch.rateRestaurant, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ restaurantId, rating: selectedRating }),
    });
    renderRestaurant(updatedRestaurant);
  } catch (err) {
    mount.insertAdjacentHTML('afterbegin', `
      <div class="alert alert-danger" role="alert">${sanitize(err.message || 'Could not save rating.')}</div>
    `);
  }
}

async function toggleFavorite() {
  const restaurantId = currentRestaurant?.id;
  if (!restaurantId) return;
  try {
    const updatedRestaurant = await fetchJson(API.whatsForLunch.favoriteRestaurant, {
      method: currentRestaurant.myFavorite ? 'DELETE' : 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ restaurantId }),
    });
    renderRestaurant(updatedRestaurant);
  } catch (err) {
    mount.insertAdjacentHTML('afterbegin', `
      <div class="alert alert-danger" role="alert">${sanitize(err.message || 'Could not update favorite.')}</div>
    `);
  }
}

mount?.addEventListener('click', async (event) => {
  const ratingButton = event.target instanceof Element
    ? event.target.closest('.lunch-rating-button')
    : null;
  if (ratingButton) {
    await rateRestaurant(ratingButton.dataset.rating);
    return;
  }

  const favoriteButton = event.target instanceof Element
    ? event.target.closest('.restaurant-favorite-toggle')
    : null;
  if (favoriteButton) {
    await toggleFavorite();
  }
});

loadRestaurant();
