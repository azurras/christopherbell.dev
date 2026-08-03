import { API } from './lib/api.js';
import { createRestaurantVoteMutation } from './lib/restaurant-vote-mutation.js';
import { authHeaders, fetchJson, getAuthClaims, sanitize } from './lib/util.js';
import { voteSummary } from './lib/wfl-ui.js';

const VOTE_OPTIONS = Object.freeze([
  Object.freeze({ value: 'UP', label: 'Thumbs up', glyph: '👍' }),
  Object.freeze({ value: 'DOWN', label: 'Thumbs down', glyph: '👎' }),
]);

function memberRestaurant(value, expectedId) {
  if (!value || typeof value !== 'object' || String(value.id || '') !== expectedId) {
    throw new Error('Restaurant controls returned invalid data.');
  }
  const summary = voteSummary(value);
  return Object.freeze({
    id: expectedId,
    name: typeof value.name === 'string' && value.name.trim()
      ? value.name.trim()
      : 'restaurant',
    upVotes: summary.upVotes,
    downVotes: summary.downVotes,
    voteCount: summary.voteCount,
    myVote: summary.myVote,
    myFavorite: value.myFavorite === true,
  });
}

function memberMarkup(restaurant) {
  return `
    <p>Your vote: ${restaurant.myVote === 'UP' ? 'Thumbs up'
      : restaurant.myVote === 'DOWN' ? 'Thumbs down' : 'Not voted'}</p>
    <div class="lunch-vote-control" role="group" aria-label="Vote on ${sanitize(restaurant.name)}">
      ${VOTE_OPTIONS.map(option => `
        <button type="button"
          class="lunch-vote-button"
          data-vote="${option.value}"
          aria-label="${option.label}"
          aria-pressed="${restaurant.myVote === option.value}">${option.glyph}</button>
      `).join('')}
    </div>
    <button type="button"
      class="btn ${restaurant.myFavorite ? 'btn-success' : 'btn-outline-success'} restaurant-favorite-toggle"
      aria-pressed="${restaurant.myFavorite ? 'true' : 'false'}">
      <span aria-hidden="true">&hearts;</span> ${restaurant.myFavorite ? 'Favorited' : 'Favorite'}
    </button>`;
}

async function saveVote(restaurantId, vote, request, headers) {
  if (!VOTE_OPTIONS.some(option => option.value === vote)) return null;
  return request(API.whatsForLunch.voteRestaurant, {
    method: 'PUT',
    headers: headers({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ restaurantId, vote }),
  });
}

/** Adds authenticated personal controls without rebuilding public profile content. */
export async function initializeRestaurantProfile({
  mount = typeof document === 'undefined'
    ? null
    : document.getElementById('restaurant-member-controls'),
  publicMount = typeof document === 'undefined'
    ? null
    : document.getElementById('restaurant-public-votes'),
  claims = getAuthClaims,
  request = fetchJson,
  headers = authHeaders,
} = {}) {
  if (!mount || !claims()?.sub) return;

  const restaurantId = String(mount.dataset.restaurantId || '').trim();
  if (!restaurantId) return;

  const anonymousFallback = mount.innerHTML;
  const state = { restaurant: null };
  mount.innerHTML = '<p class="restaurant-member-loading">Loading your vote and favorite...</p>';

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

  const voteMutation = createRestaurantVoteMutation({
    buttons: () => Array.from(mount.querySelectorAll?.('.lunch-vote-button') || []),
    apply: value => {
      const updated = render(value);
      if (publicMount) publicMount.textContent = voteSummary(updated).overall;
    },
    showError: error => {
      if (error?.status === 401) {
        state.restaurant = null;
        mount.innerHTML = anonymousFallback;
        return;
      }
      showError(error);
    },
  });

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
    const voteButton = event.target?.closest?.('.lunch-vote-button');
    const favoriteButton = event.target?.closest?.('.restaurant-favorite-toggle');
    if (!voteButton && !favoriteButton) return;

    try {
      if (voteButton) {
        const controls = Array.from(mount.querySelectorAll?.('.lunch-vote-button') || [voteButton]);
        await voteMutation(() => saveVote(
          state.restaurant.id, voteButton.dataset.vote, request, headers,
        ), controls);
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
