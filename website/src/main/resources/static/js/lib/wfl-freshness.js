import { formatWhen, sanitize } from './util.js';

/** Renders the public WFL source freshness summary. */
export function wflFreshnessMarkup(freshness) {
  if (!freshness) return '';
  const refreshed = freshness.lastRefreshedOn
    ? formatWhen(freshness.lastRefreshedOn)
    : 'Not yet imported';
  const state = freshness.lastRefreshedOn
    ? (freshness.current ? 'Current' : 'Refresh overdue')
    : 'Not yet imported';
  const coverage = Array.isArray(freshness.cityCoverage) ? freshness.cityCoverage : [];
  return `
    <aside class="wfl-freshness" aria-label="Restaurant data freshness">
      <p><strong>${sanitize(freshness.source || 'Restaurant source')}</strong>
        <span class="${freshness.current ? 'text-success' : 'text-warning'}">${sanitize(state)}</span>
        · Last refreshed ${sanitize(refreshed)}</p>
      <details>
        <summary>${coverage.length} covered cities</summary>
        <p>${coverage.map(sanitize).join(', ') || 'No city coverage configured.'}</p>
      </details>
    </aside>
  `;
}
