import assert from 'node:assert/strict';
import test from 'node:test';

import {
  authHeaders,
  fetchJson,
  getAuthClaims,
  hasAuthenticatedSession,
  isLoggedIn,
} from '../../main/resources/static/js/lib/util.js';

function installBrowserGlobals(fetchFn = async () => new Response('{}')) {
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { cookie: 'CBELL_AUTH_STATE=1; XSRF-TOKEN=csrf-value' },
  });
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: {
      location: {
        hash: '',
        href: 'https://example.test/account',
        origin: 'https://example.test',
        pathname: '/account',
        search: '',
      },
    },
  });
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem(key) {
        if (key === 'cbellLoginToken') {
          throw new Error('browser code must not read a JWT from localStorage');
        }
        return key === 'cbellRole' ? 'USER' : null;
      },
      removeItem(key) {
        if (key === 'cbellLoginToken') {
          throw new Error('browser code must not manage a JWT in localStorage');
        }
      },
    },
  });
  Object.defineProperty(globalThis, 'fetch', {
    configurable: true,
    value: fetchFn,
  });
}

test('browser authentication uses only the non-secret cookie marker', () => {
  installBrowserGlobals();

  assert.equal(isLoggedIn(), true);
  assert.deepEqual(getAuthClaims(), { sub: 'browser-session', role: 'USER' });
  assert.deepEqual(authHeaders(), { 'X-XSRF-TOKEN': 'csrf-value' });
});

test('unsafe JSON requests send same-origin cookies and the CSRF token', async () => {
  const requests = [];
  installBrowserGlobals(async (url, options) => {
    requests.push({ url, options });
    return new Response(JSON.stringify({ success: true, payload: { ok: true } }), {
      headers: { 'Content-Type': 'application/json' },
      status: 200,
    });
  });

  const result = await fetchJson('/api/example', {
    method: 'POST',
    body: JSON.stringify({ value: 1 }),
  });

  assert.deepEqual(result, { ok: true });
  assert.equal(requests[0].options.credentials, 'same-origin');
  assert.equal(requests[0].options.headers.Authorization, undefined);
  assert.equal(requests[0].options.headers['X-XSRF-TOKEN'], 'csrf-value');
});

test('a stale readable marker is rejected by the server session boundary', async () => {
  installBrowserGlobals(async () => new Response('{}', { status: 403 }));

  assert.equal(await hasAuthenticatedSession('/api/accounts/me'), false);
  assert.match(document.cookie, /CBELL_AUTH_STATE=; Max-Age=0/);
});
