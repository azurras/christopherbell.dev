import assert from 'node:assert/strict';
import test from 'node:test';

import {
  canesBoxChartMarkup,
  canesBoxChartPoints,
  canesBoxIndexTrend,
  canesBoxPeriodTrend,
  canesBoxPeriodTrends,
  canesBoxMetroTrend,
  canesBoxMetroRowsMarkup,
  canesBoxMetroTrendMarkup,
  canesBoxOfficialApiCurl,
  bindCanesBoxCurlCopyControl,
  formatCollectedDate,
  formatQualityStatus,
  formatIndexTrend,
  formatUsd
} from '../../main/resources/static/js/canes-box-tracker.js';

test('formatUsd renders prices and empty state labels', () => {
  assert.equal(formatUsd(13.2), '$13.20');
  assert.equal(formatUsd('12.99'), '$12.99');
  assert.equal(formatUsd(null), 'No data');
});

test('canesBoxChartPoints maps priced weeks into SVG coordinates', () => {
  const points = canesBoxChartPoints([
    { weekStartDate: '2026-06-01', averagePrice: 12.95 },
    { weekStartDate: '2026-06-08', averagePrice: 13.25 },
    { weekStartDate: '2026-06-15', averagePrice: null }
  ], 100, 100, 10);

  assert.equal(points.length, 2);
  assert.equal(points[0].x, 10);
  assert.equal(points[1].x, 90);
  assert.equal(points[0].y, 90);
  assert.equal(points[1].y, 10);
});

test('canesBoxChartMarkup renders an empty state without prices', () => {
  assert.match(canesBoxChartMarkup([]), /No weekly prices/);
});

test('canesBoxIndexTrend calculates higher and lower percentage movement', () => {
  assert.deepEqual(
    canesBoxIndexTrend([
      { weekStartDate: '2026-06-01', averagePrice: 10 },
      { weekStartDate: '2026-06-08', averagePrice: 11.5 },
    ]),
    { percent: 15, direction: 'higher' }
  );
  assert.deepEqual(
    canesBoxIndexTrend([
      { weekStartDate: '2026-06-01', averagePrice: 12 },
      { weekStartDate: '2026-06-08', averagePrice: 11.4 },
    ]),
    { percent: -5, direction: 'lower' }
  );
});

test('canesBoxPeriodTrend calculates month over month from closest priced week', () => {
  const trend = canesBoxPeriodTrend([
    { weekStartDate: '2026-05-04', averagePrice: 10 },
    { weekStartDate: '2026-05-11', averagePrice: null },
    { weekStartDate: '2026-05-18', averagePrice: 10.5 },
    { weekStartDate: '2026-06-15', averagePrice: 11.55 },
  ], 1);

  assert.deepEqual(trend, {
    percent: 10,
    direction: 'higher',
    latestWeekStartDate: '2026-06-15',
    comparisonWeekStartDate: '2026-05-18',
    latestPrice: 11.55,
    comparisonPrice: 10.5,
  });
});

test('formatPeriodTrendContext identifies the latest priced and comparison weeks', async () => {
  const { formatPeriodTrendContext } = await import('../../main/resources/static/js/canes-box-tracker.js');

  assert.equal(
    formatPeriodTrendContext({
      latestWeekStartDate: '2026-09-21',
      comparisonWeekStartDate: '2026-08-24',
    }),
    'Latest priced week: 2026-09-21. Compared with week of 2026-08-24.',
  );
});

test('canesBoxPeriodTrends calculates month over month and year over year', () => {
  const trends = canesBoxPeriodTrends([
    { weekStartDate: '2025-06-09', averagePrice: 9.5 },
    { weekStartDate: '2026-05-11', averagePrice: 10 },
    { weekStartDate: '2026-06-08', averagePrice: 12 },
  ]);

  assert.equal(trends.monthOverMonth.percent, 20);
  assert.equal(trends.monthOverMonth.comparisonWeekStartDate, '2026-05-11');
  assert.equal(trends.yearOverYear.percent, 26.3);
  assert.equal(trends.yearOverYear.comparisonWeekStartDate, '2025-06-09');
});

test('canesBoxPeriodTrend returns null without enough history for the period', () => {
  assert.equal(canesBoxPeriodTrend([
    { weekStartDate: '2026-06-08', averagePrice: 12 },
  ], 12), null);
  assert.equal(canesBoxPeriodTrend([
    { weekStartDate: '2026-04-14', averagePrice: 10 },
    { weekStartDate: '2026-06-08', averagePrice: 12 },
  ], 12), null);
});

test('formatIndexTrend renders signed percentages and empty states', () => {
  assert.equal(formatIndexTrend({ percent: 3.456, direction: 'higher' }), '+3.5%');
  assert.equal(formatIndexTrend({ percent: -2.111, direction: 'lower' }), '-2.1%');
  assert.equal(formatIndexTrend(null), 'No trend yet');
});

test('formatQualityStatus labels quality states for public readers', () => {
  assert.equal(formatQualityStatus('VERIFIED'), 'Verified');
  assert.equal(formatQualityStatus('PROVISIONAL'), 'Provisional');
  assert.equal(formatQualityStatus('EXCLUDED'), 'Excluded');
  assert.equal(formatQualityStatus(null), '-');
});

test('formatCollectedDate renders stable collection date labels', () => {
  assert.equal(formatCollectedDate('2026-06-08T12:34:56Z'), '2026-06-08');
  assert.equal(formatCollectedDate('2026-06-09T04:30:00Z'), '2026-06-08');
  assert.equal(formatCollectedDate('2026-06-09T05:30:00Z'), '2026-06-09');
  assert.equal(formatCollectedDate('2026-06-08'), '2026-06-08');
  assert.equal(formatCollectedDate(null), '-');
});

test('canesBoxOfficialApiCurl renders a reproducible official menu request for a metro store', () => {
  const curl = canesBoxOfficialApiCurl({ restaurantRef: 'raising-canes-140' });

  assert.match(curl, /https:\/\/gateway\.raisingcanes\.com\/v2\/api\/v1/);
  assert.match(curl, /olo-platform: web/);
  assert.match(curl, /nomnom-platform: web/);
  assert.match(curl, /restaurant\(slug: \$slug\)/);
  assert.match(curl, /"slug":"raising-canes-140"/);
});

test('Cane’s curl copy reports when the clipboard API is unavailable', async () => {
  const button = { textContent: 'Copy curl' };
  const panel = fakeCurlCopyPanel(button);
  const alerts = [];

  bindCanesBoxCurlCopyControl({
    panel,
    clipboard: null,
    showAlertFn: message => alerts.push(message),
  });

  await panel.click();

  assert.deepEqual(alerts, ['Could not copy the official API curl request.']);
  assert.equal(button.textContent, 'Copy curl');
});

test('Cane’s curl copy reports rejected clipboard writes', async () => {
  const button = { textContent: 'Copy curl' };
  const panel = fakeCurlCopyPanel(button);
  const alerts = [];

  bindCanesBoxCurlCopyControl({
    panel,
    clipboard: { writeText: async () => { throw new Error('Permission denied'); } },
    showAlertFn: message => alerts.push(message),
  });

  await panel.click();

  assert.deepEqual(alerts, ['Could not copy the official API curl request.']);
  assert.equal(button.textContent, 'Copy curl');
});

test('Cane’s curl copy confirms success and restores the original button label', async () => {
  const button = { textContent: 'Copy curl' };
  const panel = fakeCurlCopyPanel(button);
  let copiedText = null;
  let resetButton = null;
  let resetDelay = null;

  bindCanesBoxCurlCopyControl({
    panel,
    clipboard: { writeText: async text => { copiedText = text; } },
    showAlertFn: () => assert.fail('Successful copy must not show an alert.'),
    setTimeoutFn: (callback, delay) => {
      resetButton = callback;
      resetDelay = delay;
    },
  });

  await panel.click();

  assert.equal(copiedText, 'curl --get https://gateway.raisingcanes.com/v2/api/v1');
  assert.equal(button.textContent, 'Copied');
  assert.equal(resetDelay, 1600);
  resetButton();
  assert.equal(button.textContent, 'Copy curl');
});

test('canesBoxMetroRowsMarkup renders source and last collection date without quality or status columns', () => {
  const markup = canesBoxMetroRowsMarkup({
    metroPrices: [
      {
        metroName: 'Austin',
        restaurantName: 'Austin Cane\'s',
        price: 12.99,
        sourceName: 'OFFICIAL_API',
        qualityStatus: 'VERIFIED',
        status: 'SUCCESS',
        collectedOn: '2026-06-08T12:34:56Z',
      },
      {
        metroName: 'Houston',
        restaurantName: 'Houston Cane\'s',
        price: 11.49,
        sourceName: 'PUBLIC_MENU',
        qualityStatus: 'PROVISIONAL',
        status: 'SUCCESS',
        sourceFetchedOn: '2026-06-01T09:00:00Z',
      },
      {
        metroName: 'Phoenix',
        restaurantName: 'Phoenix Cane\'s',
        sourceName: 'NONE',
        qualityStatus: 'EXCLUDED',
        status: 'FAILED',
        failureReason: 'HTTP 403',
      },
    ],
  });

  assert.match(markup, /OFFICIAL_API/);
  assert.match(markup, /2026-06-08/);
  assert.match(markup, /2026-06-01/);
  assert.doesNotMatch(markup, /Verified/);
  assert.doesNotMatch(markup, /Provisional/);
  assert.doesNotMatch(markup, /Excluded/);
  assert.doesNotMatch(markup, /HTTP 403/);
});

test('canesBoxMetroTrend returns verified priced weeks for one metro', () => {
  const trend = canesBoxMetroTrend([
    {
      weekStartDate: '2026-06-01',
      metroPrices: [
        { metroName: 'Austin', price: 11.99, status: 'SUCCESS', qualityStatus: 'VERIFIED' },
        { metroName: 'Houston', price: 12.49, status: 'SUCCESS', qualityStatus: 'VERIFIED' },
      ],
    },
    {
      weekStartDate: '2026-06-08',
      metroPrices: [
        { metroName: 'Austin', price: 12.59, status: 'SUCCESS', qualityStatus: 'VERIFIED' },
      ],
    },
    {
      weekStartDate: '2026-06-15',
      metroPrices: [
        { metroName: 'Austin', price: 7.8, status: 'FAILED', qualityStatus: 'EXCLUDED' },
      ],
    },
  ], 'Austin');

  assert.equal(trend.metroName, 'Austin');
  assert.deepEqual(trend.weeks.map(week => week.weekStartDate), ['2026-06-01', '2026-06-08']);
  assert.deepEqual(trend.indexTrend, { percent: 5, direction: 'higher' });
  assert.equal(trend.latest.price, 12.59);
});

test('canesBoxMetroTrendMarkup renders metro-only trend content', () => {
  const markup = canesBoxMetroTrendMarkup({
    metroName: 'Austin',
    latest: {
      weekStartDate: '2026-06-08',
      price: 12.59,
      restaurantName: 'Austin Cane\'s',
      restaurantRef: 'raising-canes-140',
    },
    periodTrends: {
      monthOverMonth: { percent: 5, direction: 'higher' },
      yearOverYear: { percent: 12.4, direction: 'higher' },
    },
    weeks: [
      { weekStartDate: '2026-06-01', averagePrice: 11.99, price: 11.99 },
      { weekStartDate: '2026-06-08', averagePrice: 12.59, price: 12.59 },
    ],
  });

  assert.match(markup, /Austin/);
  assert.match(markup, /Austin Cane&#39;s/);
  assert.match(markup, /\+5\.0%/);
  assert.match(markup, /\+12\.4%/);
  assert.match(markup, /Weekly Austin Box Combo price/);
  assert.match(markup, /Verify with official API/);
  assert.match(markup, /raising-canes-140/);
});

function fakeCurlCopyPanel(button) {
  let clickHandler = null;
  return {
    addEventListener(name, handler) {
      if (name === 'click') clickHandler = handler;
    },
    querySelector(selector) {
      assert.equal(selector, '.canes-box-official-curl code');
      return { textContent: 'curl --get https://gateway.raisingcanes.com/v2/api/v1' };
    },
    click() {
      return clickHandler?.({
        target: {
          closest(selector) {
            assert.equal(selector, '.canes-box-copy-curl');
            return button;
          },
        },
      });
    },
  };
}
