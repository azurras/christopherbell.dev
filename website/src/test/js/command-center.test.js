import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

import { API } from '../../main/resources/static/js/lib/api.js';
import { fetchJson } from '../../main/resources/static/js/lib/util.js';
import {
  ACTION_PHRASES,
  POLL_INTERVAL_MS,
  actionCountdown,
  clearActionDialogState,
  displayMetric,
  isAccessDenied,
  nextLogPageState,
  pollRequestDecision,
  pollFailureDecision,
  metricState,
  nextPollDelay,
  shouldPoll,
  visibleLogText,
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

test('poll requests start once, queue behind one in-flight request, or stop when inactive', () => {
  assert.equal(pollRequestDecision({ hidden: false, revoked: false, inFlight: false }), 'start');
  assert.equal(pollRequestDecision({ hidden: false, revoked: false, inFlight: true }), 'queue');
  assert.equal(pollRequestDecision({ hidden: true, revoked: false, inFlight: false }), 'ignore');
  assert.equal(pollRequestDecision({ hidden: false, revoked: true, inFlight: false }), 'ignore');
});

test('stale polling generations and request cursors cannot apply log pages', () => {
  const state = { generation: 4, cursor: 'current', lastAppliedNextCursor: 'older' };

  assert.equal(nextLogPageState(state, { generation: 3, cursor: 'current' }, {
    nextCursor: 'stale', records: [{ text: 'old generation' }]
  }).apply, false);
  assert.equal(nextLogPageState(state, { generation: 4, cursor: 'old-filter' }, {
    nextCursor: 'stale', records: [{ text: 'old filter' }]
  }).apply, false);
});

test('log page state honors empty cursors and rejects same-cursor duplicate batches', () => {
  const initial = { generation: 2, cursor: 'cursor-1', lastAppliedNextCursor: 'cursor-0' };
  const emptied = nextLogPageState(initial, { generation: 2, cursor: 'cursor-1' }, {
    nextCursor: '', records: []
  });

  assert.deepEqual(emptied, {
    apply: true, generation: 2, cursor: '', lastAppliedNextCursor: ''
  });
  assert.equal(nextLogPageState(emptied, { generation: 2, cursor: '' }, {
    nextCursor: '', records: [{ text: 'duplicate' }]
  }).apply, false);
});

test('visible log copy preserves each row as exact plain text separated by newlines', () => {
  const rows = [
    { textContent: 'INFO first' },
    { textContent: '<script>alert(1)</script>' },
    { textContent: 'ERROR third' },
  ];

  assert.equal(
    visibleLogText(rows),
    'INFO first\n<script>alert(1)</script>\nERROR third'
  );
  assert.equal(visibleLogText([]), '');
});

test('only unauthorized and forbidden request errors revoke command center access', () => {
  assert.equal(isAccessDenied({ status: 401 }), true);
  assert.equal(isAccessDenied({ status: 403 }), true);
  assert.equal(isAccessDenied({ status: 500 }), false);
  assert.equal(isAccessDenied(new Error('offline')), false);
});

test('access loss revokes even when the failed poll generation became stale', () => {
  assert.equal(pollFailureDecision({ status: 401 }, false), 'revoke');
  assert.equal(pollFailureDecision({ status: 403 }, false), 'revoke');
  assert.equal(pollFailureDecision({ name: 'AbortError' }, true), 'ignore');
  assert.equal(pollFailureDecision({ status: 500 }, false), 'ignore');
  assert.equal(pollFailureDecision({ status: 500 }, true), 'retry');
});

test('fetchJson preserves forbidden HTTP status for access revocation', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => ({
    status: 403,
    ok: false,
    async json() { return { messages: [{ description: 'Forbidden' }] }; },
  });
  try {
    await assert.rejects(fetchJson('/protected'), error => error.status === 403);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('dangerous actions require exact fixed phrases', () => {
  assert.deepEqual(ACTION_PHRASES, {
    RESTART_SITE: 'RESTART SITE',
    RESTART_COMPUTER: 'RESTART COMPUTER',
    SHUTDOWN_COMPUTER: 'SHUTDOWN COMPUTER',
  });
});

test('dialog cleanup clears every sensitive and status field', () => {
  const fields = {
    passwordInput: { value: 'secret' },
    phraseInput: { value: 'RESTART SITE' },
    requiredPhrase: { textContent: 'RESTART SITE' },
    dialogStatus: { textContent: 'Wrong password' },
  };

  assert.equal(clearActionDialogState(fields), null);
  assert.deepEqual(fields, {
    passwordInput: { value: '' },
    phraseInput: { value: '' },
    requiredPhrase: { textContent: '' },
    dialogStatus: { textContent: '' },
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
  assert.match(script, /account\?\.role\s*!==\s*'ADMIN'/);
  assert.match(script, /new AbortController\(\)/);
  assert.match(script, /pollInFlight/);
  assert.match(script, /invalidatePoll/);
  assert.match(script, /revokeCommandCenterAccess/);
  assert.equal((script.match(/if \(handleRequestFailure\(error\)\)/g) || []).length, 4);
  assert.match(script, /pollFailureDecision\(error, isCurrentPoll/);
  assert.match(script, /root\.classList\.add\('d-none'\)/);
  assert.match(script, /window\.clearInterval\(sampleTimer\)/);
  assert.match(script, /actionDialog\.addEventListener\('cancel'/);
  assert.match(script, /actionDialog\.addEventListener\('close'/);
  assert.match(script, /dialogStatus\.textContent = 'The confirmation phrase must match exactly\.'/);
  assert.match(script, /dialogStatus\.textContent = error\?\.message \|\| 'Action was not accepted'/);
  const clearStart = script.indexOf("#commandLogClear').addEventListener");
  const clearHandler = script.slice(clearStart, script.indexOf('\n  });', clearStart));
  assert.match(clearHandler, /restartPollGeneration\(\)/);
  const queryStart = script.indexOf("logQuery.addEventListener('input'");
  const queryHandler = script.slice(queryStart, script.indexOf('\n  });', queryStart));
  assert.match(queryHandler, /invalidateLogStream\(\)/);
});
