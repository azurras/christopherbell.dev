import assert from 'node:assert/strict';
import test from 'node:test';

import { wflFreshnessMarkup } from '../../main/resources/static/js/lib/wfl-freshness.js';

test('freshness markup reports source, timestamp, state, and coverage safely', () => {
  const markup = wflFreshnessMarkup({
    source: 'OpenStreetMap',
    lastRefreshedOn: '2026-07-26T12:00:00Z',
    current: true,
    currentWithinDays: 45,
    cityCoverage: ['Austin, TX', '<script>alert(1)</script>'],
  });

  assert.match(markup, /OpenStreetMap/);
  assert.match(markup, /Current/);
  assert.match(markup, /Austin, TX/);
  assert.doesNotMatch(markup, /<script>/);
});

test('freshness markup is honest when no successful refresh exists', () => {
  assert.match(wflFreshnessMarkup({ source: 'OpenStreetMap', cityCoverage: [] }), /Not yet imported/);
});
