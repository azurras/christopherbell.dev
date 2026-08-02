import assert from 'node:assert/strict';
import test from 'node:test';

const componentModule = await import(
  '../../main/resources/static/js/lib/lazy-component.js'
).catch(cause => ({ loadFailure: cause }));

test('lazy component does not load without its page mount', async () => {
  assert.ifError(componentModule.loadFailure);
  let loads = 0;

  assert.equal(await componentModule.mountLazyComponent(
    null,
    'blog-posts',
    async () => { loads += 1; },
  ), false);
  assert.equal(loads, 0);
});

test('lazy component loads its definition before appending the element', async () => {
  assert.ifError(componentModule.loadFailure);
  const events = [];
  const host = {
    ownerDocument: {
      createElement: tagName => {
        events.push(`create:${tagName}`);
        return { tagName };
      },
    },
    appendChild: element => events.push(`append:${element.tagName}`),
  };

  assert.equal(await componentModule.mountLazyComponent(
    host,
    'photo-gallery',
    async () => { events.push('load'); },
  ), true);
  assert.deepEqual(events, ['load', 'create:photo-gallery', 'append:photo-gallery']);
});
