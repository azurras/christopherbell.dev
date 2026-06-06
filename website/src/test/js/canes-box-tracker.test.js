import assert from 'node:assert/strict';
import test from 'node:test';

import {
  canesBoxChartMarkup,
  canesBoxChartPoints,
  canesBoxIndexTrend,
  canesBoxMetroRowsMarkup,
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

test('canesBoxMetroRowsMarkup renders quality status and failure details', () => {
  const markup = canesBoxMetroRowsMarkup({
    metroPrices: [
      {
        metroName: 'Austin',
        restaurantName: 'Austin Cane\'s',
        price: 12.99,
        sourceName: 'OFFICIAL_API',
        qualityStatus: 'VERIFIED',
        status: 'SUCCESS',
      },
      {
        metroName: 'Houston',
        restaurantName: 'Houston Cane\'s',
        price: 11.49,
        sourceName: 'PUBLIC_MENU',
        qualityStatus: 'PROVISIONAL',
        status: 'SUCCESS',
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

  assert.match(markup, /Verified/);
  assert.match(markup, /Provisional/);
  assert.match(markup, /Excluded/);
  assert.match(markup, /HTTP 403/);
});
