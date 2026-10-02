import assert from 'node:assert/strict';
import test from 'node:test';

const sessionLink = { value: 'https://www.christopherbell.dev/wfl?session=session-123' };
const status = { textContent: '' };
let clickHandler;

class FakeElement {
  closest(selector) {
    return selector === '.lunch-session-copy' ? this : null;
  }
}

const copyButton = Object.assign(new FakeElement(), { textContent: 'Copy link' });
const mount = {
  addEventListener(name, listener) {
    if (name === 'click') clickHandler = listener;
  },
  querySelector(selector) {
    if (selector === '.lunch-session-link') return sessionLink;
    if (selector === '.lunch-session-status') return status;
    return null;
  },
};

globalThis.Element = FakeElement;
globalThis.localStorage = { getItem: () => null, removeItem() {}, setItem() {} };
globalThis.document = {
  cookie: '',
  getElementById: id => id === 'whats-for-lunch' ? mount : null,
};
globalThis.window = {
  location: {
    origin: 'https://www.christopherbell.dev',
    href: 'https://www.christopherbell.dev/wfl',
    search: '',
  },
};
globalThis.fetch = async () => ({
  ok: true,
  status: 200,
  json: async () => ({}),
});

await import('../../main/resources/static/js/whats-for-lunch.js');

function setClipboard(clipboard) {
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: clipboard === undefined ? {} : { clipboard },
  });
}

test('shared-session copy confirms the copied share link', async () => {
  const copied = [];
  setClipboard({ writeText: async value => copied.push(value) });

  await assert.doesNotReject(clickHandler({ target: copyButton }));

  assert.deepEqual(copied, [sessionLink.value]);
  assert.equal(copyButton.textContent, 'Copied');
  assert.equal(status.textContent, 'Link copied.');
});

test('shared-session copy reports clipboard rejection without an unhandled error', async () => {
  setClipboard({ writeText: async () => { throw new Error('Permission denied'); } });

  await assert.doesNotReject(clickHandler({ target: copyButton }));

  assert.match(status.textContent, /unable to copy/i);
  assert.notEqual(copyButton.textContent, 'Copied');
});

test('shared-session copy does not claim success when Clipboard API is unavailable', async () => {
  setClipboard(undefined);

  await assert.doesNotReject(clickHandler({ target: copyButton }));

  assert.match(status.textContent, /unable to copy/i);
  assert.notEqual(copyButton.textContent, 'Copied');
});
