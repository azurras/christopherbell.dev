import assert from 'node:assert/strict';
import test from 'node:test';

globalThis.localStorage = { getItem: () => null, removeItem() {}, setItem() {} };
globalThis.document = { cookie: '', getElementById: () => null };
globalThis.window = { location: { origin: 'https://www.christopherbell.dev', search: '' } };

const picksModule = await import('../../main/resources/static/js/whats-for-lunch.js');

const ACTIVE_SESSION = Object.freeze({
  id: 'active-session',
  createdByUsername: 'Chris',
  canManage: true,
  participantUsernames: ['Chris'],
  restaurants: [],
  votesByRestaurant: {},
  myVoteRestaurantId: null,
  revision: 7,
  active: true,
  canChangeRestaurants: true,
  activeUntil: '2026-08-04T11:48:51.112Z',
  createdOn: '2026-08-03T11:48:51.112Z',
  lastUpdatedOn: '2026-08-03T11:51:53.639Z',
});

const ARCHIVED_SESSION = Object.freeze({
  ...ACTIVE_SESSION,
  id: 'archived-session',
  canManage: false,
  active: false,
  canChangeRestaurants: false,
  activeUntil: '2026-08-03T20:08:40.385Z',
  createdOn: '2026-08-02T20:08:40.385Z',
  lastUpdatedOn: '2026-08-03T02:03:32.948Z',
});

function recoveryPage({
  initialSession = null,
  restoredSession = null,
  failParticipantRead = false,
} = {}) {
  const state = { session: initialSession };
  const calls = {
    clearStoredSession: 0,
    loadSession: [],
    loadSoloSession: [],
    refreshSharedSession: 0,
    stopPolling: 0,
  };
  const controller = picksModule.createSessionRecoveryController({
    getActiveSession: () => state.session,
    setActiveSession: session => { state.session = session; },
    clearStoredSession: () => { calls.clearStoredSession += 1; },
    stopPolling: () => { calls.stopPolling += 1; },
    loadSession: async (sessionId, options) => {
      calls.loadSession.push({ sessionId, options });
      if (failParticipantRead && options?.join === false) {
        const error = new Error('WFL session not found.');
        error.status = 404;
        throw error;
      }
      state.session = restoredSession;
    },
    refreshSharedSession: async () => { calls.refreshSharedSession += 1; },
    loadSoloSession: async options => {
      calls.loadSoloSession.push(options);
      return 'fresh-picks';
    },
  });
  return { calls, controller, state };
}

test('archived saved session is cleared so normal initialization can continue', async () => {
  assert.equal(typeof picksModule.createSessionRecoveryController, 'function');
  const page = recoveryPage({ restoredSession: ARCHIVED_SESSION });

  const restored = await page.controller.restoreStoredSession('archived-session');

  assert.equal(restored, false);
  assert.equal(page.state.session, null);
  assert.equal(page.calls.clearStoredSession, 1);
  assert.equal(page.calls.stopPolling, 1);
  assert.deepEqual(page.calls.loadSession, [{
    sessionId: 'archived-session',
    options: { join: false, storeSession: true },
  }]);
});

test('active saved session remains the current shared session', async () => {
  const page = recoveryPage({ restoredSession: ACTIVE_SESSION });

  const restored = await page.controller.restoreStoredSession('active-session');

  assert.equal(restored, true);
  assert.equal(page.state.session, ACTIVE_SESSION);
  assert.equal(page.calls.clearStoredSession, 0);
  assert.equal(page.calls.stopPolling, 0);
});

test('new picks from an archived session use the solo flow without shared reset', async () => {
  const page = recoveryPage({ initialSession: ARCHIVED_SESSION });

  const result = await page.controller.requestNearbyPicks();

  assert.equal(result, 'fresh-picks');
  assert.equal(page.calls.refreshSharedSession, 0);
  assert.deepEqual(page.calls.loadSoloSession, [{ forceNew: true }]);
});

test('new picks from an active session preserve the shared reset flow', async () => {
  const page = recoveryPage({ initialSession: ACTIVE_SESSION });

  await page.controller.requestNearbyPicks();

  assert.equal(page.calls.refreshSharedSession, 1);
  assert.deepEqual(page.calls.loadSoloSession, []);
});

test('explicit archived session reads existing participant state without joining', async () => {
  const page = recoveryPage({ restoredSession: ARCHIVED_SESSION });

  await page.controller.loadExplicitSession('archived-session');

  assert.equal(page.state.session, ARCHIVED_SESSION);
  assert.deepEqual(page.calls.loadSession, [{
    sessionId: 'archived-session',
    options: { join: false, storeSession: true },
  }]);
});

test('explicit active link joins when the participant read is not found', async () => {
  const page = recoveryPage({
    restoredSession: ACTIVE_SESSION,
    failParticipantRead: true,
  });

  await page.controller.loadExplicitSession('active-session');

  assert.equal(page.state.session, ACTIVE_SESSION);
  assert.deepEqual(page.calls.loadSession, [
    {
      sessionId: 'active-session',
      options: { join: false, storeSession: true },
    },
    {
      sessionId: 'active-session',
      options: { join: true, storeSession: true },
    },
  ]);
});

test('archived sessions allow a fresh pick request while active guests remain read-only', () => {
  const archivedPage = recoveryPage({ initialSession: ARCHIVED_SESSION });
  const guestPage = recoveryPage({
    initialSession: { ...ACTIVE_SESSION, canChangeRestaurants: false },
  });

  assert.equal(archivedPage.controller.canRequestNearbyPicks(), true);
  assert.equal(guestPage.controller.canRequestNearbyPicks(), false);
});
