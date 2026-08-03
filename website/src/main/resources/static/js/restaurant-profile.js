import { API } from './lib/api.js';
import { authHeaders, fetchJson, getAuthClaims, sanitize } from './lib/util.js';
import { ratingSummary } from './lib/wfl-ui.js';

const RATING_OPTIONS = Object.freeze([1, 2, 3, 4, 5]);

function memberRestaurant(value, expectedId) {
  if (!value || typeof value !== 'object' || String(value.id || '') !== expectedId) {
    throw new Error('Restaurant controls returned invalid data.');
  }
  const summary = ratingSummary(value);
  return Object.freeze({
    id: expectedId,
    name: typeof value.name === 'string' && value.name.trim()
      ? value.name.trim()
      : 'restaurant',
    ratingCount: summary.count,
    ratingSum: Number.parseInt(String(value.ratingSum ?? 0), 10) || 0,
    myRating: RATING_OPTIONS.includes(summary.myRating) ? summary.myRating : 0,
    myFavorite: value.myFavorite === true,
  });
}

function memberMarkup(restaurant) {
  return `
    <p>Your rating: ${restaurant.myRating > 0 ? `${restaurant.myRating}/5` : 'Not rated'}</p>
    <div class="lunch-rating-control" role="group" aria-label="Rate ${sanitize(restaurant.name)}">
      ${RATING_OPTIONS.map(value => `
        <button type="button"
          class="lunch-rating-button ${restaurant.myRating === value ? 'active' : ''}"
          data-rating="${value}"
          aria-label="Rate ${value} out of 5">${value}</button>
      `).join('')}
    </div>
    <button type="button"
      class="btn ${restaurant.myFavorite ? 'btn-success' : 'btn-outline-success'} restaurant-favorite-toggle"
      aria-pressed="${restaurant.myFavorite ? 'true' : 'false'}">
      <span aria-hidden="true">&hearts;</span> ${restaurant.myFavorite ? 'Favorited' : 'Favorite'}
    </button>`;
}

function aggregateRatingText(restaurant) {
  const count = Number.parseInt(String(restaurant.ratingCount ?? 0), 10);
  const sum = Number.parseInt(String(restaurant.ratingSum ?? 0), 10);
  if (!Number.isInteger(count)
      || !Number.isInteger(sum)
      || count <= 0
      || sum < count
      || sum > count * 5) {
    return 'No ratings yet';
  }
  return `${(sum / count).toFixed(1)}/5 from ${count} ${count === 1 ? 'rating' : 'ratings'}`;
}

/** Adds authenticated personal controls without rebuilding public profile content. */
export async function initializeRestaurantProfile({
  mount = typeof document === 'undefined'
    ? null
    : document.getElementById('restaurant-member-controls'),
  publicRating = typeof document === 'undefined'
    ? null
    : document.getElementById('restaurant-public-rating'),
  claims = getAuthClaims,
  request = fetchJson,
  headers = authHeaders,
} = {}) {
  if (!mount || !claims()?.sub) return;

  const restaurantId = String(mount.dataset.restaurantId || '').trim();
  if (!restaurantId) return;

  const anonymousFallback = mount.innerHTML;
  const state = { restaurant: null };
  mount.innerHTML = '<p class="restaurant-member-loading">Loading your rating and favorite...</p>';

  const render = value => {
    const restaurant = memberRestaurant(value, restaurantId);
    state.restaurant = restaurant;
    mount.innerHTML = memberMarkup(restaurant);
    return restaurant;
  };

  const showError = error => {
    mount.insertAdjacentHTML(
      'afterbegin',
      `<div class="alert alert-danger" role="alert">${sanitize(
        error?.message || 'Could not update your restaurant controls.',
      )}</div>`,
    );
  };

  try {
    render(await request(API.whatsForLunch.restaurant(restaurantId), {
      headers: headers(),
    }));
  } catch (error) {
    mount.innerHTML = anonymousFallback;
    if (error?.status !== 401) showError(error);
    return;
  }

  mount.addEventListener('click', async event => {
    const ratingButton = event.target?.closest?.('.lunch-rating-button');
    const favoriteButton = event.target?.closest?.('.restaurant-favorite-toggle');
    if (!ratingButton && !favoriteButton) return;

    try {
      if (ratingButton) {
        const rating = Number.parseInt(String(ratingButton.dataset.rating), 10);
        if (!RATING_OPTIONS.includes(rating)) return;
        const updated = render(await request(API.whatsForLunch.rateRestaurant, {
          method: 'PUT',
          headers: headers({ 'Content-Type': 'application/json' }),
          body: JSON.stringify({ restaurantId: state.restaurant.id, rating }),
        }));
        if (publicRating) {
          publicRating.textContent = aggregateRatingText(updated);
        }
        return;
      }

      render(await request(API.whatsForLunch.favoriteRestaurant, {
        method: state.restaurant.myFavorite ? 'DELETE' : 'PUT',
        headers: headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ restaurantId: state.restaurant.id }),
      }));
    } catch (error) {
      if (error?.status === 401) {
        state.restaurant = null;
        mount.innerHTML = anonymousFallback;
        return;
      }
      showError(error);
    }
  });
}

void initializeRestaurantProfile();
