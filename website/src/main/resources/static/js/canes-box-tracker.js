import { API } from './lib/api.js';
import { fetchJson, sanitize } from './lib/util.js';

const hasDocument = typeof document !== 'undefined';
const latestPrice = hasDocument ? document.getElementById('canesBoxLatestPrice') : null;
const latestWeek = hasDocument ? document.getElementById('canesBoxLatestWeek') : null;
const metroCount = hasDocument ? document.getElementById('canesBoxMetroCount') : null;
const verifiedCount = hasDocument ? document.getElementById('canesBoxVerifiedCount') : null;
const provisionalCount = hasDocument ? document.getElementById('canesBoxProvisionalCount') : null;
const excludedCount = hasDocument ? document.getElementById('canesBoxExcludedCount') : null;
const indexNumber = hasDocument ? document.getElementById('canesBoxIndexNumber') : null;
const indexContext = hasDocument ? document.getElementById('canesBoxIndexContext') : null;
const alertBox = hasDocument ? document.getElementById('canesBoxAlert') : null;
const chart = hasDocument ? document.getElementById('canesBoxChart') : null;
const metroRows = hasDocument ? document.getElementById('canesBoxMetroRows') : null;

/** Format a USD price or return a readable empty state. */
export function formatUsd(value) {
  if (value == null || Number.isNaN(Number(value))) return 'No data';
  return `$${Number(value).toFixed(2)}`;
}

/** Calculate percent movement between the latest two priced weekly averages. */
export function canesBoxIndexTrend(weeks) {
  const pricedWeeks = (weeks || []).filter(week => week.averagePrice != null);
  if (pricedWeeks.length < 2) return null;
  const previous = Number(pricedWeeks[pricedWeeks.length - 2].averagePrice);
  const latest = Number(pricedWeeks[pricedWeeks.length - 1].averagePrice);
  if (!previous || Number.isNaN(previous) || Number.isNaN(latest)) return null;
  const percent = ((latest - previous) / previous) * 100;
  return {
    percent: Math.round(percent * 10) / 10,
    direction: percent > 0 ? 'higher' : percent < 0 ? 'lower' : 'flat',
  };
}

/** Format the visible index percentage. */
export function formatIndexTrend(trend) {
  if (!trend) return 'No trend yet';
  if (trend.percent > 0) return `+${trend.percent.toFixed(1)}%`;
  return `${trend.percent.toFixed(1)}%`;
}

/** Format source quality for public table rows. */
export function formatQualityStatus(status) {
  if (!status) return '-';
  const normalized = String(status).toLowerCase();
  return `${normalized.charAt(0).toUpperCase()}${normalized.slice(1)}`;
}

/** Build normalized SVG chart points for weekly price history. */
export function canesBoxChartPoints(weeks, width = 720, height = 260, padding = 34) {
  const pricedWeeks = (weeks || []).filter(week => week.averagePrice != null);
  if (pricedWeeks.length === 0) return [];
  const prices = pricedWeeks.map(week => Number(week.averagePrice));
  const min = Math.min(...prices);
  const max = Math.max(...prices);
  const span = max - min || 1;
  return pricedWeeks.map((week, index) => {
    const x = pricedWeeks.length === 1
      ? width / 2
      : padding + (index * (width - padding * 2)) / (pricedWeeks.length - 1);
    const y = height - padding - ((Number(week.averagePrice) - min) * (height - padding * 2)) / span;
    return { ...week, x, y };
  });
}

/** Render the weekly price chart as inline SVG. */
export function canesBoxChartMarkup(weeks) {
  const width = 720;
  const height = 260;
  const points = canesBoxChartPoints(weeks, width, height);
  if (points.length === 0) {
    return '<p class="canes-box-empty">No weekly prices have been collected yet.</p>';
  }
  const path = points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(1)} ${point.y.toFixed(1)}`).join(' ');
  const dots = points.map(point => `
    <g>
      <circle cx="${point.x.toFixed(1)}" cy="${point.y.toFixed(1)}" r="5"></circle>
      <text x="${point.x.toFixed(1)}" y="${Math.max(18, point.y - 12).toFixed(1)}">${sanitize(formatUsd(point.averagePrice))}</text>
    </g>`).join('');
  return `<svg viewBox="0 0 ${width} ${height}" role="img" aria-label="Weekly average Box Combo price">
    <line class="canes-box-chart-axis" x1="34" y1="226" x2="686" y2="226"></line>
    <path class="canes-box-chart-line" d="${path}"></path>
    <g class="canes-box-chart-points">${dots}</g>
  </svg>`;
}

function showAlert(message) {
  if (!alertBox) return;
  alertBox.textContent = message;
  alertBox.classList.remove('d-none');
}

function renderLatest(latest) {
  if (latestPrice) latestPrice.textContent = formatUsd(latest?.averagePrice);
  if (latestWeek) latestWeek.textContent = latest?.weekStartDate || '-';
  if (metroCount) {
    metroCount.textContent = latest ? `${latest.successfulMetroCount}/${latest.totalMetroCount}` : '-';
  }
  if (verifiedCount) verifiedCount.textContent = latest?.verifiedMetroCount ?? '-';
  if (provisionalCount) provisionalCount.textContent = latest?.provisionalMetroCount ?? '-';
  if (excludedCount) excludedCount.textContent = latest?.excludedMetroCount ?? '-';
}

function renderIndex(weeks) {
  if (!indexNumber || !indexContext) return;
  const trend = canesBoxIndexTrend(weeks);
  indexNumber.textContent = formatIndexTrend(trend);
  indexNumber.classList.remove('canes-box-index-higher', 'canes-box-index-lower', 'canes-box-index-neutral');
  indexNumber.classList.add(trend?.direction === 'higher'
    ? 'canes-box-index-higher'
    : trend?.direction === 'lower'
      ? 'canes-box-index-lower'
      : 'canes-box-index-neutral');
  indexContext.textContent = trend
    ? `Compared with the previous priced weekly average.`
    : 'Needs two priced weekly averages.';
}


/** Build latest per-metro table rows for the public tracker page. */
export function canesBoxMetroRowsMarkup(latest) {
  const rows = latest?.metroPrices || [];
  if (rows.length === 0) {
    return '<tr><td colspan="6">No metro samples have been collected yet.</td></tr>';
  }
  return rows.map(row => `
    <tr>
      <td>${sanitize(row.metroName)}</td>
      <td>${sanitize(row.restaurantName || row.address || row.restaurantRef)}</td>
      <td>${sanitize(formatUsd(row.price))}</td>
      <td>${sanitize(row.sourceName || '-')}</td>
      <td>${sanitize(formatQualityStatus(row.qualityStatus))}</td>
      <td>${sanitize(row.status === 'SUCCESS' ? 'Collected' : row.failureReason || 'Failed')}</td>
    </tr>
  `).join('');
}

function renderMetroRows(latest) {
  if (!metroRows) return;
  metroRows.innerHTML = canesBoxMetroRowsMarkup(latest);
}

function renderHistory(history) {
  renderIndex(history?.weeks || []);
  renderLatest(history?.latest);
  if (chart) chart.innerHTML = canesBoxChartMarkup(history?.weeks || []);
  renderMetroRows(history?.latest);
}

async function loadHistory() {
  try {
    const history = await fetchJson(API.canesBoxTracker.history);
    renderHistory(history);
  } catch (err) {
    showAlert(err.message || 'Raising Canes Box Index history could not be loaded.');
    renderHistory({ latest: null, weeks: [] });
  }
}

if (hasDocument) {
  loadHistory();
}
