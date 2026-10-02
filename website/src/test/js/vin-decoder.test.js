import assert from 'node:assert/strict';
import test from 'node:test';

const elements = new Map();
const fetchCalls = [];

function fakeElement() {
  const listeners = new Map();
  const classes = new Set();
  return {
    value: '',
    textContent: '',
    disabled: false,
    classList: {
      add: (...names) => names.forEach(name => classes.add(name)),
      remove: (...names) => names.forEach(name => classes.delete(name)),
    },
    addEventListener(name, listener) { listeners.set(name, listener); },
    dispatch(name, event = {}) { return listeners.get(name)?.(event); },
  };
}

for (const id of [
  'vinDecodeForm', 'vinInput', 'vinDecodeButton', 'vinDecodeAlert', 'vinResult',
  'vinJsonOutput', 'vinCurlOutput', 'copyJsonButton', 'copyCurlButton',
  'vinMake', 'vinModel', 'vinYear', 'vinBody', 'vinPlant',
]) {
  elements.set(id, fakeElement());
}

globalThis.document = { getElementById: id => elements.get(id) || null };
globalThis.window = { location: { origin: 'https://www.christopherbell.dev' } };
globalThis.fetch = async (...args) => {
  fetchCalls.push(args);
  throw new Error('Unexpected API request');
};

await import('../../main/resources/static/js/vin-decoder.js');

test('short VIN shows local validation without consuming an API request', async () => {
  const form = elements.get('vinDecodeForm');
  const input = elements.get('vinInput');
  const alert = elements.get('vinDecodeAlert');
  input.value = '1HGCM82633A00435';

  await form.dispatch('submit', { preventDefault() {} });

  assert.equal(fetchCalls.length, 0);
  assert.equal(alert.textContent, 'VIN must be exactly 17 valid characters.');
});
