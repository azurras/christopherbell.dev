import assert from 'node:assert/strict';
import test from 'node:test';

const activePostMount = { innerHTML: '' };
let refreshActivePost;
const pendingRequests = [];

globalThis.document = {
  addEventListener() {},
  getElementById(id) {
    return id === 'homeActivePost' ? activePostMount : null;
  }
};

globalThis.window = {
  setInterval(callback, intervalMs) {
    assert.equal(intervalMs, 5000);
    refreshActivePost = callback;
    return 1;
  }
};

globalThis.fetch = url => new Promise((resolve, reject) => pendingRequests.push({ url, resolve, reject }));

await import('../../main/resources/static/js/home.js?response-order');

function feedResponse(id) {
  return {
    ok: true,
    status: 200,
    json: async () => ({
      success: true,
      payload: [{ id, username: id, text: `result ${id}`, likesCount: 1, replyCount: 0 }]
    })
  };
}

function flushPromises() {
  return new Promise(resolve => setImmediate(resolve));
}

test('older signal-rail success or failure cannot overwrite a newer result', async () => {
  assert.equal(pendingRequests.length, 1);

  refreshActivePost();
  assert.equal(pendingRequests.length, 2);

  pendingRequests[1].resolve(feedResponse('newer'));
  await flushPromises();
  assert.match(activePostMount.innerHTML, /result newer/);

  pendingRequests[0].resolve(feedResponse('older'));
  await flushPromises();

  assert.match(activePostMount.innerHTML, /result newer/);
  assert.doesNotMatch(activePostMount.innerHTML, /result older/);

  refreshActivePost();
  refreshActivePost();
  assert.equal(pendingRequests.length, 4);

  pendingRequests[3].resolve(feedResponse('latest'));
  await flushPromises();
  assert.match(activePostMount.innerHTML, /result latest/);

  pendingRequests[2].reject(new Error('stale request failed'));
  await flushPromises();

  assert.match(activePostMount.innerHTML, /result latest/);
  assert.doesNotMatch(activePostMount.innerHTML, /Could not load the active post/);
});
