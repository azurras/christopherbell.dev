import assert from 'node:assert/strict';
import test from 'node:test';
import { isPhoneBrowser, mountSurviveController, readStandardGamepad } from '../../main/resources/static/js/survive-controller.js';

test('a narrow desktop and touch laptop do not require phone play mode', () => {
  assert.equal(isPhoneBrowser({ userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)', maxTouchPoints: 10 }), false);
  assert.equal(isPhoneBrowser({ userAgent: 'Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X)' }), false);
  assert.equal(isPhoneBrowser({ userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)' }), true);
  assert.equal(isPhoneBrowser({ userAgent: 'Mozilla/5.0 (Linux; Android 15) Mobile Safari/537.36' }), true);
  assert.equal(isPhoneBrowser({ userAgentData: { mobile: true } }), true);
});

test('gamepad control requires an exposed, connected controller with standard mapping', () => {
  const standard = { index: 2, mapping: 'standard', connected: true, axes: [0, 0, 0, 0], buttons: Array(16) };
  assert.equal(readStandardGamepad({ getGamepads: () => [null, { ...standard, mapping: '' }, standard] }), standard);
  assert.equal(readStandardGamepad({ getGamepads: () => [{ ...standard, connected: false }] }), null);
  assert.equal(readStandardGamepad({ getGamepads() { throw new DOMException('Blocked', 'SecurityError'); } }), null);
});

test('phone gameplay unlocks only with real fullscreen and an available gamepad', () => {
  let nextFrame;
  let gamepad = null;
  let resumed = 0;
  let stopped = 0;
  const axes = [];
  const events = [];
  const nodes = new Map();
  const documentRoot = {
    fullscreenElement: null,
    addEventListener() {},
    removeEventListener() {},
    defaultView: {
      navigator: { userAgent: 'iPhone', getGamepads: () => [gamepad] },
      requestAnimationFrame(callback) { nextFrame = callback; return 1; },
      cancelAnimationFrame() {},
      addEventListener() {},
      removeEventListener() {},
      KeyboardEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } },
    },
    getElementById(id) {
      if (!nodes.has(id)) nodes.set(id, {
        dataset: {}, hidden: false, addEventListener() {}, removeEventListener() {},
        focus() { documentRoot.activeElement = this; },
        contains() { return false; },
        dispatchEvent(event) { events.push(event.code); },
      });
      return nodes.get(id);
    },
  };
  const byId = id => documentRoot.getElementById(id);
  const shell = byId('survivePlayShell');
  shell.requestFullscreen = () => Promise.resolve();
  byId('surviveJoin').hidden = true;
  byId('surviveWorldActions').hidden = true;
  const world = {
    stopInput() { stopped++; },
    setControllerAxes(...values) { axes.push(values); },
  };
  const phonePlay = mountSurviveController(documentRoot, world, () => resumed++);
  try {
    assert.equal(byId('surviveGame').inert, true);
    gamepad = { index: 0, mapping: 'standard', connected: true, axes: [0.4, -0.8, 0.5, 0],
      buttons: Array.from({ length: 16 }, () => ({ pressed: false })) };
    nextFrame();
    assert.equal(byId('surviveGame').inert, true);
    documentRoot.fullscreenElement = shell;
    nextFrame();
    assert.equal(byId('surviveGame').inert, false);
    assert.equal(byId('survivePhoneGate').hidden, true);
    assert.equal(resumed, 1);
    assert.deepEqual(axes.at(-1), [0.4, 0.8, 0.5]);
    gamepad.buttons[2].pressed = true;
    nextFrame();
    nextFrame();
    assert.deepEqual(events, ['Minus']);
    gamepad = null;
    nextFrame();
    assert.equal(byId('surviveGame').inert, true);
    assert.deepEqual(axes.at(-1), [0, 0, 0]);
    assert.equal(stopped, 2);
    gamepad = { index: 1, mapping: 'standard', connected: true, axes: [0, 0, 0, 0],
      buttons: Array.from({ length: 16 }, () => ({ pressed: false })) };
    nextFrame();
    assert.equal(byId('surviveGame').inert, false);
    gamepad.buttons[0].pressed = true;
    byId('surviveStartOver').hidden = true;
    nextFrame();
    assert.deepEqual(events, ['Minus', 'Enter']);
    documentRoot.fullscreenElement = null;
    nextFrame();
    assert.equal(byId('surviveGame').inert, true);
  } finally { phonePlay.dispose(); }
});

test('a desktop controller works in a normal window and never locks mouse or keyboard play', () => {
  let nextFrame;
  let delayedPoll;
  let gamepad = { index: 0, mapping: 'standard', connected: true, axes: [0.55, 0, -0.7, 0],
    buttons: Array.from({ length: 16 }, () => ({ pressed: false })) };
  const axes = [];
  const nodes = new Map();
  const body = {};
  const documentRoot = {
    body, activeElement: body, fullscreenElement: null,
    removeEventListener() {},
    getElementById(id) {
      if (!nodes.has(id)) nodes.set(id, {
        dataset: {}, hidden: ['surviveJoin', 'surviveWorldActions', 'survivePhoneGate'].includes(id),
        focus() { documentRoot.activeElement = this; }, removeEventListener() {},
      });
      return nodes.get(id);
    },
    defaultView: {
      navigator: { userAgent: 'Windows NT', getGamepads: () => [gamepad] },
      addEventListener() {}, removeEventListener() {}, cancelAnimationFrame() {}, clearTimeout() {},
      requestAnimationFrame(callback) { nextFrame = callback; },
      setTimeout(callback) { delayedPoll = callback; },
    },
  };
  const controller = mountSurviveController(documentRoot, { setControllerAxes(...values) { axes.push(values); } }, () => {});
  try {
    assert.equal(documentRoot.getElementById('surviveGame').inert, undefined);
    assert.equal(documentRoot.getElementById('survivePhoneGate').hidden, true);
    assert.deepEqual(axes.at(-1), [0.55, -0, -0.7]);
    gamepad = null;
    nextFrame();
    assert.deepEqual(axes.at(-1), [0, 0, 0]);
    assert.equal(typeof delayedPoll, 'function');
    assert.equal(documentRoot.getElementById('surviveGame').inert, undefined);
  } finally { controller.dispose(); }
});
