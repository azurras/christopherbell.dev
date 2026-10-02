import assert from 'node:assert/strict';
import test from 'node:test';

const elements = new Map();

function fakeElement() {
  const listeners = new Map();
  return {
    value: '',
    textContent: '',
    disabled: false,
    classList: {
      add() {},
      remove() {},
    },
    addEventListener(name, listener) { listeners.set(name, listener); },
    dispatch(name, event = {}) { return listeners.get(name)?.(event); },
  };
}

for (const id of [
  'zipCoordinateForm', 'zipCoordinateInput', 'zipCoordinateButton', 'zipCoordinateAlert',
  'zipCoordinateResult', 'zipCoordinateCode', 'zipLatitude', 'zipLongitude', 'zipSource',
  'zipSourceYear', 'zipApiUrl', 'zipCurlOutput', 'copyZipApiButton', 'copyZipCurlButton',
]) {
  elements.set(id, fakeElement());
}

globalThis.document = { getElementById: id => elements.get(id) || null };
globalThis.window = { location: { origin: 'https://www.christopherbell.dev' } };

await import('../../main/resources/static/js/zip-coordinates.js');

test('clipboard rejection shows an actionable error on the ZIP coordinate page', async () => {
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: { clipboard: { writeText: async () => { throw new Error('Permission denied'); } } },
  });

  await elements.get('copyZipApiButton').dispatch('click');
  await new Promise(resolve => setTimeout(resolve, 0));

  assert.match(elements.get('zipCoordinateAlert').textContent, /unable to copy/i);
});
