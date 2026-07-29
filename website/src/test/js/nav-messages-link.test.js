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
  accountNavigationAccess,
  hideNavPanel,
  isActiveNavHref,
  messagesNavHref,
  profileMenuItems,
  topLevelNavItems,
  toolsMenuItems
} = await import('../../main/resources/static/js/components/nav.js');

test('navigation access defaults closed and derives every capability from one account snapshot', () => {
  assert.deepEqual(accountNavigationAccess(null), {
    isAdmin: false,
    hasMusicRead: false,
    hasSharedFolderRead: false,
  });
  assert.deepEqual(accountNavigationAccess({ role: 'ADMIN', permissions: [] }), {
    isAdmin: true,
    hasMusicRead: true,
    hasSharedFolderRead: true,
  });
  assert.deepEqual(accountNavigationAccess({
    role: 'USER', permissions: ['MUSIC_WRITE', 'SHARED_FOLDER_READ'],
  }), {
    isAdmin: false,
    hasMusicRead: true,
    hasSharedFolderRead: true,
  });
});

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

test('Tools keeps public entries and Shared Folder effective-read gating', () => {
  assert.equal(toolsMenuItems().some((item) => item.href === '/shared'), false);
  assert.deepEqual(
    toolsMenuItems({ hasSharedFolderRead: true }).map((item) => item.label),
    ['Raising Canes Box Index', 'Shared Folder', 'VIN Decoder', "What's For Lunch", 'ZIP Coordinates']
  );
});

test('Tools gates moved destinations and sorts every visible item alphabetically', () => {
  assert.equal(toolsMenuItems().some((item) => item.href === '/music'), false);
  assert.deepEqual(
    toolsMenuItems({ hasMusicRead: true }).map((item) => item.label),
    ['Music', 'Raising Canes Box Index', 'VIN Decoder', "What's For Lunch", 'ZIP Coordinates']
  );
  assert.deepEqual(
    toolsMenuItems({ isAdmin: true, hasSharedFolderRead: true }).map((item) => item.label),
    ['Back Office', 'Command Center', 'Music', 'Raising Canes Box Index', 'Shared Folder',
      'VIN Decoder', "What's For Lunch", 'ZIP Coordinates']
  );
});

test('void nav uses Feed as the primary Void link', () => {
  assert.deepEqual(
    topLevelNavItems(true).find((item) => item.href === '/void'),
    { href: '/void', label: 'Feed' }
  );
});

test('Explore is public and owns Explore and topic route highlighting', () => {
  assert.deepEqual(
    topLevelNavItems(false).find((item) => item.href === '/void/explore'),
    { href: '/void/explore', label: 'Explore' }
  );
  assert.equal(isActiveNavHref('/void/explore', '/void/explore'), true);
  assert.equal(isActiveNavHref('/void/explore', '/void/topic/music'), true);
  assert.equal(isActiveNavHref('/void', '/void/topic/music'), false);
});

test('moved destinations no longer appear in the top-level or profile menus', () => {
  assert.equal(topLevelNavItems(false).some((item) => item.href === '/music'), false);
  assert.equal(topLevelNavItems(true).some((item) => item.href === '/music'), false);
  assert.deepEqual(profileMenuItems(), [{ href: '/profile', label: 'Profile' }]);
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
