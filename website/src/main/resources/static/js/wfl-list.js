import { API } from './lib/api.js';
import { createRestaurantVoteMutation } from './lib/restaurant-vote-mutation.js';
import { authHeaders, fetchJson, getAuthClaims, loginRedirectUrl, sanitize } from './lib/util.js';
import { wflFreshnessMarkup } from './lib/wfl-freshness.js';
import {
  formatCuisine,
  voteSummary,
  restaurantAddressLine,
  wflSecondaryNavigation,
} from './lib/wfl-ui.js';

const VOTES = Object.freeze([
  Object.freeze({ value: 'UP', label: 'Thumbs up', glyph: '👍' }),
  Object.freeze({ value: 'DOWN', label: 'Thumbs down', glyph: '👎' }),
]);

/** Initializes the Favorites or Top Liked list around one owned state boundary. */
export async function initializeWflList({
  mount = document.getElementById('wfl-list'),
  claims = getAuthClaims,
  request = fetchJson,
  headers = authHeaders,
  loginUrl = loginRedirectUrl,
} = {}) {
  if (!mount) return;

  const mode = mount.dataset.listMode || 'top-liked';
  const title = mount.dataset.listTitle
    || (mode === 'favorites' ? 'Favorite Restaurants' : 'Top 10 Liked');
  const isLoggedIn = !!claims()?.sub;
  const voteMutations = new Map();
  let restaurants = [];
  let dataFreshness = null;

  function voteSummaryMarkup(restaurant) {
    return `<p class="lunch-vote-summary">${voteSummary(restaurant).overall}</p>`;
  }

  function voteControlsMarkup(restaurant) {
    const id = restaurant.id || '';
    if (!isLoggedIn || !id) return '';
    const summary = voteSummary(restaurant);
    const restaurantName = sanitize(restaurant.name || 'restaurant');
    return `<div class="lunch-vote-control" role="group" aria-label="Vote on ${restaurantName}">
      ${VOTES.map(option => `<button type="button" class="lunch-vote-button wfl-list-vote"
        data-restaurant-id="${sanitize(id)}" data-vote="${option.value}"
        aria-label="${option.label}" aria-pressed="${summary.myVote === option.value}">${option.glyph}</button>`).join('')}
    </div>`;
  }

  function restaurantCard(restaurant, index) {
    const id = restaurant.id || '';
    const href = id ? `/wfl/restaurants/${encodeURIComponent(id)}` : '/wfl';
    const address = restaurantAddressLine(restaurant.address);
    const cuisine = restaurant.cuisine
      ? `<span class="lunch-cuisine">${sanitize(formatCuisine(restaurant.cuisine))}</span>`
      : '';
    const favoriteButton = isLoggedIn && id
      ? `<button type="button" class="btn ${restaurant.myFavorite ? 'btn-success' : 'btn-outline-success'} btn-sm wfl-list-favorite" data-restaurant-id="${sanitize(id)}" aria-pressed="${restaurant.myFavorite ? 'true' : 'false'}">
          <span aria-hidden="true">&hearts;</span> ${restaurant.myFavorite ? 'Favorited' : 'Favorite'}
        </button>`
      : '';
    return `
      <article class="lunch-pick wfl-list-card" data-restaurant-id="${sanitize(id)}">
        <div class="lunch-pick-rank">${index + 1}</div>
        <div class="lunch-pick-body">
          <div class="lunch-pick-header">
            <h2><a href="${sanitize(href)}">${sanitize(restaurant.name || 'Restaurant')}</a></h2>
            ${favoriteButton}
          </div>
          ${cuisine}
          ${address ? `<p>${sanitize(address)}</p>` : ''}
          ${voteSummaryMarkup(restaurant)}
          ${voteControlsMarkup(restaurant)}
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
      : 'No liked restaurants yet.';
    mount.innerHTML = `
      ${wflSecondaryNavigation(mode)}
      ${wflFreshnessMarkup(dataFreshness)}
      <div class="wfl-list-heading">
        <h2>${sanitize(title)}</h2>
        <p>${mode === 'favorites'
          ? 'Restaurants you have marked as favorites.'
          : 'WFL restaurants with the highest member approval.'}</p>
      </div>
      ${restaurants.length > 0
        ? `<div class="lunch-picks wfl-list">${restaurants.map(restaurantCard).join('')}</div>`
        : `<div class="lunch-empty"><h2>${emptyText}</h2><a class="btn btn-outline-primary" href="/wfl">Back to picks</a></div>`}
    `;
  }

  function renderLoginPrompt() {
    mount.innerHTML = `
      ${wflSecondaryNavigation('favorites')}
      ${wflFreshnessMarkup(dataFreshness)}
      <div class="lunch-empty">
        <h2>Sign in to see favorites</h2>
        <p>Your favorite restaurants are saved to your account.</p>
        <a class="btn btn-primary" href="${sanitize(loginUrl('/wfl/favorites'))}">Log in</a>
      </div>
    `;
  }

  function renderError(error) {
    mount.innerHTML = `
      ${wflSecondaryNavigation(mode)}
      ${wflFreshnessMarkup(dataFreshness)}
      <div class="lunch-empty">
        <h2>Could not load restaurants</h2>
        <p>${sanitize(error.message || 'Please try again later.')}</p>
        <button type="button" class="btn btn-outline-primary wfl-list-retry">Try again</button>
      </div>
    `;
  }

  function controlsFor(restaurantId) {
    return Array.from(mount.querySelectorAll('.wfl-list-vote'))
      .filter(button => button.dataset.restaurantId === restaurantId);
  }

  function cardFor(restaurantId) {
    return Array.from(mount.querySelectorAll('.wfl-list-card'))
      .find(card => card.dataset.restaurantId === restaurantId);
  }

  function renderVoteError(restaurantId, error) {
    const card = cardFor(restaurantId);
    if (!card) return;
    card.querySelector?.('.wfl-list-vote-error')?.remove();
    card.insertAdjacentHTML('afterbegin', `
      <div class="alert alert-danger wfl-list-vote-error" role="alert">${
        sanitize(error.message || 'Could not save vote.')
      }</div>
    `);
  }

  function mutationFor(restaurantId) {
    if (!voteMutations.has(restaurantId)) {
      voteMutations.set(restaurantId, createRestaurantVoteMutation({
        buttons: () => controlsFor(restaurantId),
        apply: value => {
          if (!value || typeof value !== 'object' || value.id !== restaurantId) {
            throw new Error('Vote service returned an invalid restaurant.');
          }
          restaurants = restaurants.map(restaurant =>
            restaurant.id === restaurantId ? value : restaurant);
          renderList();
        },
        showError: error => renderVoteError(restaurantId, error),
      }));
    }
    return voteMutations.get(restaurantId);
  }

  async function setRestaurantVote(restaurantId, vote) {
    if (!isLoggedIn || !restaurantId || !VOTES.some(option => option.value === vote)) return;
    await mutationFor(restaurantId)(() => request(API.whatsForLunch.voteRestaurant, {
      method: 'PUT',
      headers: headers({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ restaurantId, vote }),
    }));
  }

  async function toggleFavorite(restaurantId) {
    if (!restaurantId) return;
    const restaurant = restaurants.find(candidate => candidate.id === restaurantId);
    const isFavorite = !!restaurant?.myFavorite;
    try {
      const updated = await request(API.whatsForLunch.favoriteRestaurant, {
        method: isFavorite ? 'DELETE' : 'PUT',
        headers: headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ restaurantId }),
      });
      restaurants = mode === 'favorites' && isFavorite
        ? restaurants.filter(candidate => candidate.id !== restaurantId)
        : restaurants.map(candidate => candidate.id === restaurantId ? updated : candidate);
      renderList();
    } catch (error) {
      mount.insertAdjacentHTML('afterbegin', `
        <div class="alert alert-danger" role="alert">${
          sanitize(error.message || 'Could not update favorite.')
        }</div>
      `);
    }
  }

  async function loadRestaurants() {
    try {
      dataFreshness = await request(API.whatsForLunch.freshness);
    } catch (_) {
      dataFreshness = null;
    }
    if (mode === 'favorites' && !isLoggedIn) {
      renderLoginPrompt();
      return;
    }

    mount.innerHTML = `
      ${wflSecondaryNavigation(mode)}
      <div class="lunch-empty"><p>Loading restaurants...</p></div>
    `;
    try {
      const value = await request(
        mode === 'favorites' ? API.whatsForLunch.favorites : API.whatsForLunch.topLiked(10),
        { headers: headers() },
      );
      restaurants = Array.isArray(value) ? value : [];
      renderList();
    } catch (error) {
      if (mode === 'favorites' && error.message === 'Authentication required.') {
        renderLoginPrompt();
        return;
      }
      renderError(error);
    }
  }

  mount.addEventListener('click', async event => {
    const voteButton = event.target instanceof Element
      ? event.target.closest('.wfl-list-vote')
      : null;
    if (voteButton) {
      await setRestaurantVote(voteButton.dataset.restaurantId, voteButton.dataset.vote);
      return;
    }

    const favoriteButton = event.target instanceof Element
      ? event.target.closest('.wfl-list-favorite')
      : null;
    if (favoriteButton) {
      favoriteButton.disabled = true;
      try {
        await toggleFavorite(favoriteButton.dataset.restaurantId);
      } finally {
        favoriteButton.disabled = false;
      }
      return;
    }

    const retryButton = event.target instanceof Element
      ? event.target.closest('.wfl-list-retry')
      : null;
    if (retryButton) await loadRestaurants();
  });

  await loadRestaurants();
}

initializeWflList();
