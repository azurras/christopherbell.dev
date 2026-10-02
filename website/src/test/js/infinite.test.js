import assert from 'node:assert/strict';
import test from 'node:test';

import { createInfiniteScroller } from '../../main/resources/static/js/lib/infinite.js';

test('infinite scroller reports an initial load failure and permits another attempt', async () => {
  const errors = [];
  let attempts = 0;
  let emptyPages = 0;
  let successfulPages = 0;
  const scroller = createInfiniteScroller({
    async fetchPage() {
      attempts += 1;
      if (attempts === 1) throw new Error('Feed unavailable');
      return [];
    },
    onPage: () => assert.fail('An empty page must not render feed items.'),
    onEmpty: () => { emptyPages += 1; },
    onError: error => errors.push(error.message),
    onSuccess: () => { successfulPages += 1; },
  });

  await scroller.loadInitial();
  assert.deepEqual(errors, ['Feed unavailable']);

  await scroller.loadInitial();
  assert.deepEqual(errors, ['Feed unavailable']);
  assert.equal(attempts, 2);
  assert.equal(emptyPages, 1);
  assert.equal(successfulPages, 1);
});

test('infinite scroller reports malformed page data and permits a retry', async () => {
  const errors = [];
  const rendered = [];
  let attempts = 0;
  const scroller = createInfiniteScroller({
    async fetchPage() {
      attempts += 1;
      if (attempts === 1) return { items: 'not-an-array', nextCursor: 'ignored' };
      return { items: [{ createdOn: 'recovered' }], nextCursor: null };
    },
    onPage: items => rendered.push(...items),
    onError: error => errors.push(error.message),
  });

  await scroller.loadInitial();
  assert.deepEqual(errors, ['Feed page response must contain an items array.']);

  await scroller.loadInitial();
  assert.deepEqual(rendered.map(item => item.createdOn), ['recovered']);
  assert.equal(attempts, 2);
});

test('infinite scroller follows advancing cursors across empty pages', async () => {
  const errors = [];
  const cursors = [];
  const rendered = [];
  let successfulPages = 0;
  const scroller = createInfiniteScroller({
    async fetchPage({ cursor }) {
      cursors.push(cursor);
      if (cursors.length === 1) return { items: [], nextCursor: 'first-empty' };
      if (cursors.length === 2) return { items: [], nextCursor: 'second-empty' };
      return { items: [{ createdOn: 'after-empty-pages' }], nextCursor: null };
    },
    onPage: items => rendered.push(...items),
    onError: error => errors.push(error.message),
    onSuccess: () => { successfulPages += 1; },
  });

  await scroller.loadInitial();

  assert.deepEqual(cursors, [null, 'first-empty', 'second-empty']);
  assert.deepEqual(errors, []);
  assert.deepEqual(rendered.map(item => item.createdOn), ['after-empty-pages']);
  assert.equal(successfulPages, 3);
});

test('infinite scroller stops repeated empty-page cursors and can be retried', async () => {
  const errors = [];
  const rendered = [];
  let attempts = 0;
  const scroller = createInfiniteScroller({
    async fetchPage() {
      attempts += 1;
      if (attempts <= 2) return { items: [], nextCursor: 'repeated' };
      return { items: [{ createdOn: 'recovered' }], nextCursor: null };
    },
    onPage: items => rendered.push(...items),
    onError: error => errors.push(error.message),
  });

  await scroller.loadInitial();
  assert.equal(attempts, 2);
  assert.deepEqual(errors, ['Feed page cursor did not advance.']);

  await scroller.loadInitial();
  assert.deepEqual(rendered.map(item => item.createdOn), ['recovered']);
  assert.equal(attempts, 3);
});

test('infinite scroller rejects a repeated cursor after a rendered page', async () => {
  const previousWindow = globalThis.window;
  const previousDocument = globalThis.document;
  let scrollHandler = null;
  globalThis.window = {
    innerHeight: 100,
    scrollY: 0,
    addEventListener(name, handler) {
      if (name === 'scroll') scrollHandler = handler;
    },
    removeEventListener() {},
  };
  globalThis.document = { body: { offsetHeight: 50 } };

  try {
    const errors = [];
    const rendered = [];
    let attempts = 0;
    const scroller = createInfiniteScroller({
      async fetchPage() {
        attempts += 1;
        if (attempts === 1) return { items: [{ createdOn: 'first' }], nextCursor: 'repeated' };
        if (attempts === 2) return { items: [{ createdOn: 'duplicate' }], nextCursor: 'repeated' };
        return { items: [{ createdOn: 'recovered' }], nextCursor: null };
      },
      onPage: items => rendered.push(...items),
      onError: error => errors.push(error.message),
    });
    scroller.attach();

    await scroller.loadInitial();
    await scrollHandler();

    assert.deepEqual(errors, ['Feed page cursor did not advance.']);
    assert.deepEqual(rendered.map(item => item.createdOn), ['first']);
    assert.equal(attempts, 2);

    await scroller.loadInitial();
    assert.deepEqual(rendered.map(item => item.createdOn), ['first', 'recovered']);
    assert.equal(attempts, 3);
  } finally {
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
    if (previousDocument === undefined) delete globalThis.document;
    else globalThis.document = previousDocument;
  }
});

test('infinite scroller reports a scroll failure and retries the same cursor', async () => {
  const previousWindow = globalThis.window;
  const previousDocument = globalThis.document;
  let scrollHandler = null;
  globalThis.window = {
    innerHeight: 100,
    scrollY: 0,
    addEventListener(name, handler) {
      if (name === 'scroll') scrollHandler = handler;
    },
    removeEventListener() {},
  };
  globalThis.document = { body: { offsetHeight: 50 } };

  try {
    const errors = [];
    const pages = [];
    let successfulPages = 0;
    let attempts = 0;
    const scroller = createInfiniteScroller({
      limit: 1,
      async fetchPage({ cursor }) {
        attempts += 1;
        if (!cursor) return { items: [{ createdOn: 'first' }], nextCursor: 'next' };
        if (attempts === 2) throw new Error('Next page unavailable');
        return { items: [{ createdOn: 'second' }], nextCursor: null };
      },
      onPage: items => pages.push(...items),
      onError: error => errors.push(error.message),
      onSuccess: () => { successfulPages += 1; },
    });
    scroller.attach();
    await scroller.loadInitial();
    assert.deepEqual(pages.map(page => page.createdOn), ['first']);

    await scrollHandler();
    assert.deepEqual(errors, ['Next page unavailable']);

    await scrollHandler();
    assert.deepEqual(pages.map(page => page.createdOn), ['first', 'second']);
    assert.equal(attempts, 3);
    assert.equal(successfulPages, 2);
  } finally {
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
    if (previousDocument === undefined) delete globalThis.document;
    else globalThis.document = previousDocument;
  }
});

test('a new initial load ignores stale results and keeps its loading lock', async () => {
  const previousWindow = globalThis.window;
  const previousDocument = globalThis.document;
  let scrollHandler = null;
  globalThis.window = {
    innerHeight: 100,
    scrollY: 0,
    addEventListener(name, handler) {
      if (name === 'scroll') scrollHandler = handler;
    },
    removeEventListener() {},
  };
  globalThis.document = { body: { offsetHeight: 50 } };

  try {
    const requests = [];
    const errors = [];
    const rendered = [];
    const successfulPages = [];
    const scroller = createInfiniteScroller({
      fetchPage(args) {
        return new Promise((resolve, reject) => requests.push({ args, resolve, reject }));
      },
      onPage: items => rendered.push(...items),
      onError: error => errors.push(error.message),
      onSuccess: () => { successfulPages.push('loaded'); },
    });
    scroller.attach();

    const staleFailure = scroller.loadInitial();
    const staleSuccess = scroller.loadInitial();
    requests[0].reject(new Error('Obsolete request failed'));
    await staleFailure;

    const currentLoad = scroller.loadInitial();
    requests[1].resolve({
      items: [{ createdOn: 'stale-filter' }],
      nextCursor: 'stale-cursor',
    });
    await staleSuccess;

    const scrollLoad = scrollHandler();
    const requestsWhileCurrentLoadIsPending = requests.length;
    requests[2].resolve({
      items: [{ createdOn: 'current-filter' }],
      nextCursor: null,
    });
    await currentLoad;
    if (requests[3]) {
      requests[3].resolve({ items: [], nextCursor: null });
      await scrollLoad;
    }

    assert.deepEqual(errors, []);
    assert.deepEqual(rendered.map(item => item.createdOn), ['current-filter']);
    assert.deepEqual(successfulPages, ['loaded']);
    assert.equal(requestsWhileCurrentLoadIsPending, 3);
  } finally {
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
    if (previousDocument === undefined) delete globalThis.document;
    else globalThis.document = previousDocument;
  }
});
