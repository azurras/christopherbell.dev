import assert from 'node:assert/strict';
import test from 'node:test';

const statusMessage = await import('../../main/resources/static/js/lib/status-message.js')
  .catch(cause => ({ loadFailure: cause }));

test('alert rendering safely reveals an existing host without changing its severity', () => {
  assert.ifError(statusMessage.loadFailure);
  const host = fakeHost(['alert', 'alert-warning', 'd-none']);

  assert.equal(statusMessage.renderAlert(host, '<img src=x onerror=alert(1)>'), true);

  assert.equal(host.textContent, '<img src=x onerror=alert(1)>');
  assert.deepEqual(host.classes(), ['alert', 'alert-warning']);
});

test('alert rendering replaces severity with an allowlisted class', () => {
  assert.ifError(statusMessage.loadFailure);
  const host = fakeHost(['alert', 'alert-danger', 'd-none']);

  assert.equal(statusMessage.renderAlert(host, 'Done', 'success'), true);

  assert.equal(host.textContent, 'Done');
  assert.deepEqual(host.classes(), ['alert', 'alert-success']);
});

test('alert rendering is a no-op without a host and defaults invalid severity to danger', () => {
  assert.ifError(statusMessage.loadFailure);
  assert.equal(statusMessage.renderAlert(null, 'Ignored'), false);
  const host = fakeHost(['alert', 'alert-info']);

  statusMessage.renderAlert(host, '', 'not-a-severity');

  assert.deepEqual(host.classes(), ['alert', 'alert-danger']);
});

function fakeHost(initialClasses) {
  const values = new Set(initialClasses);
  return {
    textContent: '',
    classList: {
      add: (...names) => names.forEach(name => values.add(name)),
      remove: (...names) => names.forEach(name => values.delete(name)),
    },
    classes: () => [...values].sort(),
  };
}
