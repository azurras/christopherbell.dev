import assert from 'node:assert/strict';
import test from 'node:test';

test('home feed failure removes skeletons and keeps the accessible alert', async () => {
  const previousDocument = globalThis.document;
  const feedList = fakeElement(['skeleton', 'skeleton']);
  const homeAlert = fakeAlert();
  globalThis.document = {
    addEventListener() {},
    getElementById(id) {
      return id === 'feedList' ? feedList : id === 'homeAlert' ? homeAlert : null;
    },
  };

  try {
    const { renderFeedLoadError } = await import('../../main/resources/static/js/home-feed.js');
    renderFeedLoadError(new Error('Feed unavailable'));

    assert.deepEqual(feedList.children, []);
    assert.equal(homeAlert.textContent, 'Feed unavailable');
    assert.equal(homeAlert.classList.contains('d-none'), false);
  } finally {
    restoreDocument(previousDocument);
  }
});

test('home feed failure leaves already rendered posts in place', async () => {
  const previousDocument = globalThis.document;
  const post = { type: 'post' };
  const feedList = fakeElement([post]);
  const homeAlert = fakeAlert();
  globalThis.document = {
    addEventListener() {},
    getElementById(id) {
      return id === 'feedList' ? feedList : id === 'homeAlert' ? homeAlert : null;
    },
  };

  try {
    const { renderFeedLoadError } = await import('../../main/resources/static/js/home-feed.js');
    renderFeedLoadError(new Error('Next page unavailable'), {
      list: feedList,
      alert: homeAlert,
      hasRenderedItems: true,
    });

    assert.deepEqual(feedList.children, [post]);
    assert.equal(feedList.clearCount, 0);
    assert.equal(homeAlert.textContent, 'Next page unavailable');
  } finally {
    restoreDocument(previousDocument);
  }
});

test('home feed recovery clears its own alert after a successful page', async () => {
  const previousDocument = globalThis.document;
  const feedList = fakeElement([]);
  const homeAlert = fakeAlert();
  globalThis.document = {
    addEventListener() {},
    getElementById(id) {
      return id === 'feedList' ? feedList : id === 'homeAlert' ? homeAlert : null;
    },
  };

  try {
    const { renderFeedLoadError, clearFeedLoadError } = await import('../../main/resources/static/js/home-feed.js');
    renderFeedLoadError(new Error('Feed unavailable'));
    clearFeedLoadError();

    assert.equal(homeAlert.textContent, '');
    assert.equal(homeAlert.classList.contains('d-none'), true);
  } finally {
    restoreDocument(previousDocument);
  }
});

test('home feed recovery leaves a newer non-feed alert visible', async () => {
  const previousDocument = globalThis.document;
  const feedList = fakeElement([]);
  const homeAlert = fakeAlert();
  globalThis.document = {
    addEventListener() {},
    getElementById(id) {
      return id === 'feedList' ? feedList : id === 'homeAlert' ? homeAlert : null;
    },
  };

  try {
    const { renderFeedLoadError, clearFeedLoadError } = await import('../../main/resources/static/js/home-feed.js');
    renderFeedLoadError(new Error('Feed unavailable'));
    homeAlert.textContent = 'Composer submission failed';
    clearFeedLoadError();

    assert.equal(homeAlert.textContent, 'Composer submission failed');
    assert.equal(homeAlert.classList.contains('d-none'), false);
  } finally {
    restoreDocument(previousDocument);
  }
});

function fakeElement(children) {
  return {
    children,
    clearCount: 0,
    replaceChildren(...nextChildren) {
      this.clearCount += 1;
      this.children = nextChildren;
    },
  };
}

function fakeAlert() {
  const classes = new Set(['d-none']);
  return {
    textContent: '',
    classList: {
      add: name => classes.add(name),
      remove: name => classes.delete(name),
      contains: name => classes.has(name),
    },
  };
}

function restoreDocument(previousDocument) {
  if (previousDocument === undefined) delete globalThis.document;
  else globalThis.document = previousDocument;
}
