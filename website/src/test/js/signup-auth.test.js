import assert from 'node:assert/strict';
import test from 'node:test';

Object.defineProperty(globalThis, 'document', {
  configurable: true,
  value: { addEventListener() {} },
});
Object.defineProperty(globalThis, 'window', {
  configurable: true,
  value: {
    location: {
      origin: 'https://example.test',
      search: '',
    },
  },
});

const { signupPayload } = await import('../../main/resources/static/js/auth/signup.js');

test('signup requires and trims both names before creating an account', () => {
  assert.throws(
    () => signupPayload({ email: 'a@example.test', username: 'alpha', firstName: ' ', lastName: 'Bell', password: 'password' }),
    /first name/i,
  );
  assert.throws(
    () => signupPayload({ email: 'a@example.test', username: 'alpha', firstName: 'Chris', lastName: '\t', password: 'password' }),
    /last name/i,
  );
  assert.deepEqual(signupPayload({
    email: ' a@example.test ',
    username: ' alpha ',
    firstName: ' Chris ',
    lastName: ' Bell ',
    password: 'password',
  }), {
    email: 'a@example.test',
    username: 'alpha',
    firstName: 'Chris',
    lastName: 'Bell',
    password: 'password',
  });
});

