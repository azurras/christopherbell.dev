import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

import { API } from '../../main/resources/static/js/lib/api.js';
import {
  ACTION_PHRASES,
  POLL_INTERVAL_MS,
  actionCountdown,
  displayMetric,
  metricState,
  nextPollDelay,
  shouldPoll,
} from '../../main/resources/static/js/lib/command-center.js';

test('command center API contract uses the five fixed protected paths', () => {
  assert.deepEqual(API.admin.commandCenter, {
    snapshot: '/api/admin/command-center/2026-07-12/snapshot',
    logs: '/api/admin/command-center/2026-07-12/logs',
    challenges: '/api/admin/command-center/2026-07-12/action-challenges',
    actions: '/api/admin/command-center/2026-07-12/actions',
    cancel: '/api/admin/command-center/2026-07-12/actions/cancel',
  });
});

test('security exposes only the data-free page shell, never command center APIs', () => {
  const security = fs.readFileSync(
    'website/src/main/java/dev/christopherbell/configuration/security/SecurityConfig.java',
    'utf8'
  );

  assert.match(security, /"\/command-center"/);
  assert.doesNotMatch(security, /"[^"\r\n]*\/api\/admin\/command-center/);
});

test('metric display distinguishes unavailable and stale readings', () => {
  assert.equal(displayMetric(null), 'Unavailable');
  assert.equal(displayMetric({ status: 'UNAVAILABLE', value: null }), 'Unavailable');
  assert.equal(displayMetric({ status: 'AVAILABLE', value: 42.04, unit: '%' }), '42 %');
  assert.equal(displayMetric({ status: 'STALE', value: 7.25, unit: 'GB' }), '7.3 GB');
  assert.equal(metricState({ status: 'STALE' }), 'stale');
  assert.equal(metricState({ status: 'ERROR' }), 'unavailable');
});

test('polling uses five seconds then bounded 10, 20, and 30 second backoff', () => {
  assert.equal(POLL_INTERVAL_MS, 5000);
  assert.deepEqual([0, 1, 2, 3, 9].map(nextPollDelay), [5000, 10000, 20000, 30000, 30000]);
});

test('polling pauses for hidden tabs or explicit log pause', () => {
  assert.equal(shouldPoll(false, false), true);
  assert.equal(shouldPoll(true, false), false);
  assert.equal(shouldPoll(false, true), false);
});

test('dangerous actions require exact fixed phrases', () => {
  assert.deepEqual(ACTION_PHRASES, {
    RESTART_SITE: 'RESTART SITE',
    RESTART_COMPUTER: 'RESTART COMPUTER',
    SHUTDOWN_COMPUTER: 'SHUTDOWN COMPUTER',
  });
});

test('pending action countdown is bounded at zero and reports cancellability', () => {
  assert.deepEqual(actionCountdown('2026-07-12T12:01:00Z', Date.parse('2026-07-12T12:00:00Z'), true), {
    seconds: 60,
    cancellable: true,
    expired: false,
  });
  assert.deepEqual(actionCountdown('2026-07-12T11:59:59Z', Date.parse('2026-07-12T12:00:00Z'), false), {
    seconds: 0,
    cancellable: false,
    expired: true,
  });
});

test('page orchestration inserts log records as text and clears passwords', () => {
  const script = fs.readFileSync('website/src/main/resources/static/js/command-center.js', 'utf8');

  assert.match(script, /line\.textContent\s*=/);
  assert.doesNotMatch(script, /logOutput\.innerHTML/);
  assert.match(script, /passwordInput\.value\s*=\s*''/);
  assert.match(script, /body\?\.payload\?\.role\s*!==\s*'ADMIN'/);
});
