import assert from 'node:assert/strict';
import test from 'node:test';

import { API } from '../../main/resources/static/js/lib/api.js';
import { normalizeZipInput, zipCoordinateCurl } from '../../main/resources/static/js/zip-coordinates.js';

test('location API helper builds ZIP coordinate endpoint', () => {
  assert.equal(API.location.zipCoordinate('78701-1234'), '/api/location/zip/78701-1234');
});

test('ZIP coordinate page normalizes ZIP+4 input before lookup', () => {
  assert.equal(normalizeZipInput(' 78701-1234 '), '78701');
});

test('ZIP coordinate page rejects malformed ZIP input', () => {
  assert.equal(normalizeZipInput('abc'), '');
});

test('ZIP coordinate page builds a copyable curl example', () => {
  const curl = zipCoordinateCurl('http://localhost:8081', '78701');

  assert.match(curl, /curl 'http:\/\/localhost:8081\/api\/location\/zip\/78701'/);
});
