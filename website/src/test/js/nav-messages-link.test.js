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

const {
  hideNavPanel,
  isActiveNavHref,
  messagesNavHref,
  topLevelNavItems,
  toolsMenuItems
} = await import('../../main/resources/static/js/components/nav.js');

test('messages nav link sends signed-out users to login and back to messages', () => {
  assert.equal(messagesNavHref(false), '/login?redirect=%2Fmessages');
});

test('messages nav link sends signed-in users directly to messages', () => {
  assert.equal(messagesNavHref(true), '/messages');
});

test('tools menu exposes ZIP coordinate lookup', () => {
  assert.deepEqual(
    toolsMenuItems().find((item) => item.href === '/zip-coordinates'),
    { href: '/zip-coordinates', label: 'ZIP Coordinates' }
  );
});

test('tools menu stays alphabetized by label', () => {
  assert.deepEqual(
    toolsMenuItems().map((item) => item.label),
    ['Raising Canes Box Index', 'VIN Decoder', "What's For Lunch", 'ZIP Coordinates']
  );
});

test('void nav uses Feed as the primary Void link', () => {
  assert.deepEqual(
    topLevelNavItems(true).find((item) => item.href === '/void'),
    { href: '/void', label: 'Feed' }
  );
});

test('tools menu includes What’s For Lunch instead of top-level WFL', () => {
  assert.deepEqual(
    toolsMenuItems().find((item) => item.href === '/wfl'),
    { href: '/wfl', label: "What's For Lunch" }
  );
  assert.equal(topLevelNavItems(true).some((item) => item.href === '/wfl'), false);
});

test('messages route highlights Messages instead of Feed', () => {
  assert.equal(isActiveNavHref('/messages', '/messages'), true);
  assert.equal(isActiveNavHref('/void', '/messages'), false);
});

test('tools routes only highlight their matching dropdown item', () => {
  assert.equal(isActiveNavHref('/wfl', '/wfl'), true);
  assert.equal(isActiveNavHref('/vin-decoder', '/wfl'), false);
  assert.equal(isActiveNavHref('/zip-coordinates', '/wfl'), false);
});

test('hideNavPanel closes a nav panel and resets the trigger state', () => {
  const classes = new Set(['show']);
  const panel = {
    classList: {
      add(className) {
        classes.add(className);
      },
      remove(className) {
        classes.delete(className);
      },
      contains(className) {
        return classes.has(className);
      }
    }
  };
  const triggerAttributes = {};
  const trigger = {
    setAttribute(name, value) {
      triggerAttributes[name] = value;
    }
  };

  hideNavPanel(panel, trigger, 'show');

  assert.equal(classes.has('show'), false);
  assert.equal(triggerAttributes['aria-expanded'], 'false');
});
