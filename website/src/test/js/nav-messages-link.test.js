import assert from 'node:assert/strict';
import test from 'node:test';

globalThis.HTMLElement = class {};
globalThis.customElements = { define() {} };
globalThis.window = {
  location: {
    origin: 'http://localhost:8081',
    pathname: '/',
    search: '',
    hash: ''
  }
};
globalThis.localStorage = {
  getItem() {
    return '';
  },
  setItem() {},
  removeItem() {}
};
globalThis.document = {
  createElement() {
    return {
      textContent: '',
      get innerHTML() {
        return this.textContent;
      }
    };
  },
  addEventListener() {},
  removeEventListener() {}
};

const { messagesNavHref } = await import('../../main/resources/static/js/components/nav.js');

test('messages nav link sends signed-out users to login and back to messages', () => {
  assert.equal(messagesNavHref(false), '/login?redirect=%2Fmessages');
});

test('messages nav link sends signed-in users directly to messages', () => {
  assert.equal(messagesNavHref(true), '/messages');
});
