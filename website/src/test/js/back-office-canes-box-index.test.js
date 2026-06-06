import assert from 'node:assert/strict';
import test from 'node:test';

const { canesBoxIndexResultMarkup } =
    await import('../../main/resources/static/js/lib/back-office-canes-box-index.js');

test('canes box index operation markup summarizes a forced pull', () => {
  const markup = canesBoxIndexResultMarkup({
    weekStartDate: '2026-06-01',
    averagePrice: 13.24,
    successfulMetroCount: 14,
    totalMetroCount: 15,
    verifiedMetroCount: 12,
    provisionalMetroCount: 2,
    excludedMetroCount: 1,
    metroPrices: [
      {
        metroName: 'Dallas-Fort Worth',
        sourceName: 'PUBLIC_MENU',
        price: 11.49,
        qualityStatus: 'PROVISIONAL',
      },
    ],
  });

  assert.match(markup, /Raising Canes Box Index pull complete/);
  assert.match(markup, /\$13\.24/);
  assert.match(markup, /2026-06-01/);
  assert.match(markup, /14\/15/);
  assert.match(markup, /12/);
  assert.match(markup, /Provisional/);
  assert.match(markup, /Excluded/);
  assert.match(markup, /data-canes-box-review="approve"/);
  assert.match(markup, /data-canes-box-review="reject"/);
});

test('canes box index operation markup handles missing averages', () => {
  const markup = canesBoxIndexResultMarkup({
    weekStartDate: '2026-06-01',
    averagePrice: null,
    successfulMetroCount: 0,
    totalMetroCount: 15,
  });

  assert.match(markup, /No data/);
  assert.match(markup, /0\/15/);
});
