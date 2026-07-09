import { API } from './lib/api.js';
import { fetchJson, sanitize } from './lib/util.js';

const hasDocument = typeof document !== 'undefined';
const DAY_IN_MS = 24 * 60 * 60 * 1000;
const CENTRAL_DATE_FORMATTER = new Intl.DateTimeFormat('en-US', {
  timeZone: 'America/Chicago',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});
const CANES_OFFICIAL_GRAPHQL_URL = 'https://gateway.raisingcanes.com/v2/api/v1';
const CANES_OFFICIAL_HEADERS = [
  ['Accept', 'application/json'],
  ['Content-Type', 'application/json'],
  ['Origin', 'https://order.raisingcanes.com'],
  ['Referer', 'https://order.raisingcanes.com/'],
  ['User-Agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36'],
  ['olo-platform', 'web'],
  ['nomnom-platform', 'web'],
  ['platform', 'web'],
  ['app-version', 'prod'],
];
const latestPrice = hasDocument ? document.getElementById('canesBoxLatestPrice') : null;
const latestWeek = hasDocument ? document.getElementById('canesBoxLatestWeek') : null;
const metroCount = hasDocument ? document.getElementById('canesBoxMetroCount') : null;
const verifiedCount = hasDocument ? document.getElementById('canesBoxVerifiedCount') : null;
const provisionalCount = hasDocument ? document.getElementById('canesBoxProvisionalCount') : null;
const excludedCount = hasDocument ? document.getElementById('canesBoxExcludedCount') : null;
const monthIndexNumber = hasDocument ? document.getElementById('canesBoxMonthIndexNumber') : null;
const monthIndexContext = hasDocument ? document.getElementById('canesBoxMonthIndexContext') : null;
const yearIndexNumber = hasDocument ? document.getElementById('canesBoxYearIndexNumber') : null;
const yearIndexContext = hasDocument ? document.getElementById('canesBoxYearIndexContext') : null;
const alertBox = hasDocument ? document.getElementById('canesBoxAlert') : null;
const chart = hasDocument ? document.getElementById('canesBoxChart') : null;
const metroTrendPanel = hasDocument ? document.getElementById('canesBoxMetroTrendPanel') : null;
const metroRows = hasDocument ? document.getElementById('canesBoxMetroRows') : null;

let currentHistory = { latest: null, weeks: [] };
let selectedMetroName = '';

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

function parsedWeekDate(weekStartDate) {
  const parts = String(weekStartDate || '').split('-').map(Number);
  if (parts.length !== 3 || parts.some(Number.isNaN)) return null;
  return new Date(Date.UTC(parts[0], parts[1] - 1, parts[2]));
}

function targetDateMonthsBefore(date, monthsBack) {
  const target = new Date(date.getTime());
  target.setUTCMonth(target.getUTCMonth() - monthsBack);
  return target;
}

function pricedWeeksByDate(weeks) {
  return (weeks || [])
    .map(week => ({ ...week, priceDate: parsedWeekDate(week.weekStartDate) }))
    .filter(week => week.averagePrice != null && week.priceDate)
    .sort((left, right) => left.priceDate - right.priceDate);
}

function maxComparisonDistanceDays(monthsBack) {
  return monthsBack >= 12 ? 35 : 10;
}

/** Calculate percent movement from latest priced week to the closest historical period week. */
export function canesBoxPeriodTrend(weeks, monthsBack) {
  const pricedWeeks = pricedWeeksByDate(weeks);
  if (pricedWeeks.length < 2) return null;
  const latest = pricedWeeks[pricedWeeks.length - 1];
  const targetDate = targetDateMonthsBefore(latest.priceDate, monthsBack);
  const candidates = pricedWeeks.filter(week => week.priceDate < latest.priceDate);
  if (!candidates.length) return null;
  const closest = candidates
    .map(week => ({ week, distance: Math.abs(week.priceDate - targetDate) }))
    .sort((left, right) => left.distance - right.distance || right.week.priceDate - left.week.priceDate)[0];
  if (closest.distance > maxComparisonDistanceDays(monthsBack) * DAY_IN_MS) {
    return null;
  }
  const comparison = closest.week;
  const previous = Number(comparison.averagePrice);
  const current = Number(latest.averagePrice);
  if (!previous || Number.isNaN(previous) || Number.isNaN(current)) return null;
  const percent = ((current - previous) / previous) * 100;
  return {
    percent: Math.round(percent * 10) / 10,
    direction: percent > 0 ? 'higher' : percent < 0 ? 'lower' : 'flat',
    latestWeekStartDate: latest.weekStartDate,
    comparisonWeekStartDate: comparison.weekStartDate,
    latestPrice: current,
    comparisonPrice: previous,
  };
}

/** Calculate both public index periods from weekly history. */
export function canesBoxPeriodTrends(weeks) {
  return {
    monthOverMonth: canesBoxPeriodTrend(weeks, 1),
    yearOverYear: canesBoxPeriodTrend(weeks, 12),
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

/** Format the latest collection timestamp as a stable date-only label. */
export function formatCollectedDate(value) {
  if (!value) return '-';
  const text = String(value);
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return text;
  const date = new Date(text);
  if (Number.isNaN(date.getTime())) return '-';
  const parts = CENTRAL_DATE_FORMATTER.formatToParts(date)
    .reduce((result, part) => ({ ...result, [part.type]: part.value }), {});
  if (!parts.year || !parts.month || !parts.day) return '-';
  return `${parts.year}-${parts.month}-${parts.day}`;
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
export function canesBoxChartMarkup(weeks, label = 'Weekly average Box Combo price') {
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
  return `<svg viewBox="0 0 ${width} ${height}" role="img" aria-label="${sanitize(label)}">
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
  const trends = canesBoxPeriodTrends(weeks);
  renderIndexMetric(
    monthIndexNumber,
    monthIndexContext,
    trends.monthOverMonth,
    'No MoM yet',
    'Needs a priced week near one month ago.');
  renderIndexMetric(
    yearIndexNumber,
    yearIndexContext,
    trends.yearOverYear,
    'No YoY yet',
    'Needs a priced week near one year ago.');
}

function renderIndexMetric(numberElement, contextElement, trend, emptyLabel, emptyContext) {
  if (!numberElement || !contextElement) return;
  numberElement.textContent = trend ? formatIndexTrend(trend) : emptyLabel;
  numberElement.classList.remove('canes-box-index-higher', 'canes-box-index-lower', 'canes-box-index-neutral');
  numberElement.classList.add(trend?.direction === 'higher'
    ? 'canes-box-index-higher'
    : trend?.direction === 'lower'
      ? 'canes-box-index-lower'
      : 'canes-box-index-neutral');
  contextElement.textContent = trend
    ? `Compared with week of ${trend.comparisonWeekStartDate}.`
    : emptyContext;
}

function normalizeMetroName(value) {
  return String(value ?? '').toLowerCase().replace(/[^a-z0-9]+/g, '');
}

function isVerifiedMetroPrice(row) {
  if (!row || row.price == null || row.status !== 'SUCCESS') return false;
  return row.qualityStatus === 'VERIFIED' || !row.qualityStatus;
}

function shellSingleQuote(value) {
  return "'" + String(value).replace(/'/g, "'\"'\"'") + "'";
}

function officialRestaurantQuery() {
  return 'query Restaurant($slug: String) { restaurant(slug: $slug) { id slug menu { categories { products { name cost } } } } }';
}

/** Build a reproducible official Cane's menu API request for the selected store. */
export function canesBoxOfficialApiCurl(row) {
  const slug = row?.restaurantRef;
  if (!slug) return '';
  const payload = JSON.stringify({
    query: officialRestaurantQuery(),
    variables: { slug },
    operationName: 'Restaurant',
  });
  return [
    `curl ${shellSingleQuote(CANES_OFFICIAL_GRAPHQL_URL)} \\`,
    ...CANES_OFFICIAL_HEADERS.map(([name, value]) => `  -H ${shellSingleQuote(`${name}: ${value}`)} \\`),
    `  --data-raw ${shellSingleQuote(payload)}`,
  ].join('\n');
}

function canesBoxOfficialCurlMarkup(row) {
  const curl = canesBoxOfficialApiCurl(row);
  if (!curl) return '';
  return `
    <section class="canes-box-official-curl" aria-label="Official API verification request">
      <div class="canes-box-official-curl-header">
        <div>
          <p class="profile-label">Official API</p>
          <h3>Verify with official API</h3>
        </div>
        <button type="button" class="btn btn-outline-light btn-sm canes-box-copy-curl">Copy curl</button>
      </div>
      <p>Fetch the official menu for the selected tracked store and check the Box Combo cost.</p>
      <pre><code>${sanitize(curl)}</code></pre>
    </section>
  `;
}

/** Build verified week-by-week trend data for one metro. */
export function canesBoxMetroTrend(weeks, metroName) {
  const requested = normalizeMetroName(metroName);
  const trendWeeks = (weeks || [])
    .map(week => {
      const row = (week.metroPrices || []).find(candidate =>
        normalizeMetroName(candidate.metroName) === requested);
      if (!isVerifiedMetroPrice(row)) return null;
      return {
        ...row,
        weekStartDate: week.weekStartDate,
        collectedOn: row.collectedOn || week.collectedOn,
        averagePrice: row.price,
      };
    })
    .filter(Boolean);
  return {
    metroName,
    latest: trendWeeks.length ? trendWeeks[trendWeeks.length - 1] : null,
    indexTrend: canesBoxIndexTrend(trendWeeks),
    periodTrends: canesBoxPeriodTrends(trendWeeks),
    weeks: trendWeeks,
  };
}

/** Render a focused metro trend panel from already-loaded history. */
export function canesBoxMetroTrendMarkup(trend) {
  if (!trend?.metroName) {
    return '<p class="canes-box-empty">Select a metro from the latest samples to see its weekly trend.</p>';
  }
  if (!trend.weeks?.length) {
    return `<p class="canes-box-empty">No verified trend data is available for ${sanitize(trend.metroName)} yet.</p>`;
  }
  const monthTrend = trend.periodTrends?.monthOverMonth;
  const yearTrend = trend.periodTrends?.yearOverYear;
  const latest = trend.latest;
  const chartMarkup = canesBoxChartMarkup(trend.weeks, `Weekly ${trend.metroName} Box Combo price`);
  return `
    <div class="canes-box-metro-trend-summary">
      <div>
        <p class="profile-label">Metro</p>
        <strong>${sanitize(trend.metroName)}</strong>
      </div>
      <div>
        <p class="profile-label">Latest price</p>
        <strong>${sanitize(formatUsd(latest?.price))}</strong>
      </div>
      <div>
        <p class="profile-label">Latest week</p>
        <strong>${sanitize(latest?.weekStartDate || '-')}</strong>
      </div>
      <div>
        <p class="profile-label">Month over month</p>
        <strong class="${trendClass(monthTrend)}">${sanitize(monthTrend ? formatIndexTrend(monthTrend) : 'No MoM yet')}</strong>
      </div>
      <div>
        <p class="profile-label">Year over year</p>
        <strong class="${trendClass(yearTrend)}">${sanitize(yearTrend ? formatIndexTrend(yearTrend) : 'No YoY yet')}</strong>
      </div>
    </div>
    <p class="canes-box-metro-trend-store">${sanitize(latest?.restaurantName || latest?.address || latest?.restaurantRef || '')}</p>
    <div class="canes-box-chart canes-box-metro-chart">${chartMarkup}</div>
    ${canesBoxOfficialCurlMarkup(latest)}
  `;
}

function trendClass(trend) {
  return trend?.direction === 'higher'
    ? 'canes-box-index-higher'
    : trend?.direction === 'lower'
      ? 'canes-box-index-lower'
      : 'canes-box-index-neutral';
}

/** Build latest per-metro table rows for the public tracker page. */
export function canesBoxMetroRowsMarkup(latest, selectedMetro = '') {
  const rows = latest?.metroPrices || [];
  if (rows.length === 0) {
    return '<tr><td colspan="5">No metro samples have been collected yet.</td></tr>';
  }
  return rows.map(row => `
    <tr class="${normalizeMetroName(row.metroName) === normalizeMetroName(selectedMetro) ? 'canes-box-metro-row-selected' : ''}">
      <td>
        <button type="button" class="canes-box-metro-button" data-metro="${sanitize(row.metroName)}">
          ${sanitize(row.metroName)}
        </button>
      </td>
      <td>${sanitize(row.restaurantName || row.address || row.restaurantRef)}</td>
      <td>${sanitize(formatUsd(row.price))}</td>
      <td>${sanitize(row.sourceName || '-')}</td>
      <td>${sanitize(formatCollectedDate(row.collectedOn || row.sourceFetchedOn))}</td>
    </tr>
  `).join('');
}

function renderMetroTrend() {
  if (!metroTrendPanel) return;
  metroTrendPanel.innerHTML = canesBoxMetroTrendMarkup(canesBoxMetroTrend(currentHistory.weeks || [], selectedMetroName));
}

function renderMetroRows(latest) {
  if (!metroRows) return;
  metroRows.innerHTML = canesBoxMetroRowsMarkup(latest, selectedMetroName);
}

function renderHistory(history) {
  currentHistory = history || { latest: null, weeks: [] };
  renderIndex(history?.weeks || []);
  renderLatest(history?.latest);
  if (chart) chart.innerHTML = canesBoxChartMarkup(history?.weeks || []);
  renderMetroTrend();
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
  metroRows?.addEventListener('click', event => {
    const button = event.target.closest?.('.canes-box-metro-button');
    if (!button) return;
    selectedMetroName = button.dataset.metro || '';
    renderMetroTrend();
    renderMetroRows(currentHistory.latest);
  });
  metroTrendPanel?.addEventListener('click', async event => {
    const button = event.target.closest?.('.canes-box-copy-curl');
    if (!button) return;
    const code = metroTrendPanel.querySelector('.canes-box-official-curl code')?.textContent;
    if (!code || typeof navigator === 'undefined' || !navigator.clipboard) return;
    const originalText = button.textContent;
    try {
      await navigator.clipboard.writeText(code);
      button.textContent = 'Copied';
      setTimeout(() => {
        button.textContent = originalText;
      }, 1600);
    } catch (err) {
      showAlert('Could not copy the official API curl request.');
    }
  });
}
