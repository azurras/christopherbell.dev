import assert from 'node:assert/strict';
import test from 'node:test';

import {
  federationConsentControlModel,
  normalizeFederationConsentStatus,
} from '../../main/resources/static/js/lib/federation-consent.js';

test('federation status accepts only authoritative booleans', () => {
  assert.deepEqual(normalizeFederationConsentStatus({
    enabled: true,
    enrollmentAvailable: false,
  }), {
    enabled: true,
    enrollmentAvailable: false,
  });
  assert.throws(
    () => normalizeFederationConsentStatus({ enabled: 'true', enrollmentAvailable: true }),
    /invalid/i,
  );
});

test('unavailable enrollment blocks new opt-in but still permits an existing opt-out', () => {
  assert.deepEqual(federationConsentControlModel({
    enabled: false,
    enrollmentAvailable: false,
  }), {
    checked: false,
    disabled: true,
    message: 'Federation enrollment is not available on this server right now.',
  });
  assert.equal(federationConsentControlModel({
    enabled: true,
    enrollmentAvailable: false,
  }).disabled, false);
});

test('busy federation updates temporarily disable the control', () => {
  assert.equal(federationConsentControlModel({
    enabled: true,
    enrollmentAvailable: true,
  }, true).disabled, true);
});
