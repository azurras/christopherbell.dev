const NAV_ITEMS = Object.freeze([
  { key: 'picks', href: '/wfl', label: 'Picks' },
  { key: 'top-liked', href: '/wfl/top-liked', label: 'Top 10 Liked' },
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

export function voteSummary(restaurant = {}) {
  const upVotes = Number.parseInt(String(restaurant.upVotes ?? 0), 10) || 0;
  const downVotes = Number.parseInt(String(restaurant.downVotes ?? 0), 10) || 0;
  const voteCount = Number.parseInt(String(restaurant.voteCount ?? 0), 10) || 0;
  const myVote = ['UP', 'DOWN'].includes(restaurant.myVote) ? restaurant.myVote : null;
  const approvalPercentage = voteCount > 0 ? Math.round(upVotes * 100 / voteCount) : null;
  return Object.freeze({
    upVotes,
    downVotes,
    voteCount,
    myVote,
    approvalPercentage,
    overall: voteCount > 0
      ? `${approvalPercentage}% liked · ${upVotes} up · ${downVotes} down`
      : 'No votes yet',
  });
}
