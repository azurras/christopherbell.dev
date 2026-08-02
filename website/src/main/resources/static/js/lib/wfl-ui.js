const NAV_ITEMS = Object.freeze([
  { key: 'picks', href: '/wfl', label: 'Picks' },
  { key: 'top-rated', href: '/wfl/top-rated', label: 'Top 10 Rated' },
  { key: 'favorites', href: '/wfl/favorites', label: 'Favorites' },
]);

export function wflSecondaryNavigation(active = 'picks') {
  return `
    <nav class="wfl-secondary-nav" aria-label="What's For Lunch navigation">
      ${NAV_ITEMS.map((item) => `
        <a class="${active === item.key ? 'active' : ''}" href="${item.href}">${item.label}</a>
      `).join('')}
    </nav>
  `;
}

export function restaurantAddressLine(address = {}, includeStreet2 = false) {
  return [
    address.street1,
    includeStreet2 ? address.street2 : null,
    address.city,
    address.state,
    address.postalCode,
  ].filter(Boolean).join(', ');
}

export function formatCuisine(value) {
  return String(value || '')
    .split(/([;,/|])/)
    .map(part => /^[;,/|]$/.test(part) ? `${part} ` : part
      .trim()
      .replace(/[_-]+/g, ' ')
      .split(/\s+/)
      .filter(Boolean)
      .map(word => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
      .join(' '))
    .join('')
    .replace(/\s+/g, ' ')
    .trim();
}

export function ratingSummary(restaurant = {}) {
  const sum = Number.parseInt(String(restaurant.ratingSum ?? 0), 10) || 0;
  const count = Number.parseInt(String(restaurant.ratingCount ?? 0), 10) || 0;
  const myRating = Number.parseInt(String(restaurant.myRating ?? 0), 10) || 0;
  return Object.freeze({
    count,
    myRating,
    overall: count > 0 ? `${Math.round(sum / count)}/5` : 'No Ratings',
  });
}
