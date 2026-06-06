import { sanitize } from './util.js';

/** Format a Raising Canes Box Index price for Back Office summaries. */
export function formatCanesBoxIndexPrice(value) {
  if (value == null || Number.isNaN(Number(value))) return 'No data';
  return `$${Number(value).toFixed(2)}`;
}

/** Build Back Office status markup for a forced index pull. */
export function canesBoxIndexResultMarkup(result) {
  const provisionalRows = (result?.metroPrices || [])
      .filter(row => row?.qualityStatus === 'PROVISIONAL')
      .map(row => `
        <div class="operation-review-row">
          <span><strong>${sanitize(row.metroName || 'Metro')}</strong>${sanitize(row.sourceName || 'Source')} ${formatCanesBoxIndexPrice(row.price)}</span>
          <span class="operation-actions">
            <button type="button" class="btn btn-sm btn-outline-light" data-canes-box-review="approve" data-week-start-date="${sanitize(result?.weekStartDate || '')}" data-metro-name="${sanitize(row.metroName || '')}">Approve</button>
            <button type="button" class="btn btn-sm btn-outline-danger" data-canes-box-review="reject" data-week-start-date="${sanitize(result?.weekStartDate || '')}" data-metro-name="${sanitize(row.metroName || '')}">Reject</button>
          </span>
        </div>
      `)
      .join('');
  return `
    <p class="operation-message">Raising Canes Box Index pull complete.</p>
    <div class="operation-stat-grid">
      <span><strong>${formatCanesBoxIndexPrice(result?.averagePrice)}</strong>Average</span>
      <span><strong>${sanitize(result?.weekStartDate || '-')}</strong>Week</span>
      <span><strong>${result?.successfulMetroCount ?? 0}/${result?.totalMetroCount ?? 0}</strong>Metros</span>
      <span><strong>${result?.verifiedMetroCount ?? 0}</strong>Verified</span>
      <span><strong>${result?.provisionalMetroCount ?? 0}</strong>Provisional</span>
      <span><strong>${result?.excludedMetroCount ?? 0}</strong>Excluded</span>
    </div>
    ${provisionalRows ? `<div class="operation-review-list">${provisionalRows}</div>` : ''}
  `;
}
