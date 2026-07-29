import assert from 'node:assert/strict';
import test from 'node:test';

import { API } from '../../main/resources/static/js/lib/api.js';

test('restaurant inventory URL sends bounded nonblank search and cursor controls', () => {
  assert.equal(API.whatsForLunch.inventory({
    name: '  cafe ',
    city: ' Austin ',
    state: 'TX',
    cursor: 'next-token',
    size: 25,
  }), '/api/whatsforlunch/restaurant/2026-07-29/inventory?size=25&name=cafe&city=Austin&state=TX&cursor=next-token');
});

test('duplicate preview URL is explicitly bounded', () => {
  assert.equal(
    API.whatsForLunch.dedupeNamesPreviewPage('next-token', 25),
    '/api/whatsforlunch/restaurant/2026-07-26/dedupe-names/preview?size=25&cursor=next-token',
  );
});
