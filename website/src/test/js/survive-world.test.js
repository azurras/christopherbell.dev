import assert from 'node:assert/strict';
import test from 'node:test';
import { advancePosition, actionsAt, cameraMatrix, projectPoint, targetActionsFor } from '../../main/resources/static/js/survive-world-model.js';

test('the island offers Check and Engage after selection and weapon skills only when confirmed', () => {
  const encounter = { status: 'COMBAT', actions: ['ATTACK', 'ENGAGE', 'DEFEND', 'FLEE'] };
  assert.deepEqual(targetActionsFor(encounter, false), ['SELECT_TARGET', 'FLEE']);
  assert.deepEqual(targetActionsFor(encounter, true), ['CHECK_TARGET', 'ENGAGE', 'FLEE']);
  const engaged = { ...encounter, nextStrikeAt: '2026-11-05T14:00:00Z',
    actions: ['DISENGAGE', 'DEFEND', 'FLEE'] };
  assert.deepEqual(targetActionsFor(engaged, true), ['DEFEND', 'DISENGAGE', 'FLEE']);
  assert.deepEqual(targetActionsFor({ ...engaged, actions: [...engaged.actions, 'WEAPON_SKILL'] }, true),
    ['WEAPON_SKILL', 'DEFEND', 'DISENGAGE', 'FLEE']);
});
import { mountSurviveWorld } from '../../main/resources/static/js/survive-world.js';

test('walking is camera relative, normalizes diagonals and stays on the island', () => {
  const start = { x: 0, z: 0, heading: 0 };
  assert.equal(advancePosition(start, 0, 1, 0, 1).z, -6);
  const diagonal = advancePosition(start, 1, 1, 0, 1);
  assert.ok(Math.abs(Math.hypot(diagonal.x, diagonal.z) - 6) < 0.001);
  assert.ok(advancePosition(start, 0, 1, Math.PI / 2, 1).x < -5.9);
  assert.ok(Math.hypot(...Object.values(advancePosition({ x: 0, z: 77 }, 0, -1, 0, 1)).slice(0, 2)) <= 78);
});

test('landmarks filter actions without inventing rewards or bypassing server availability', () => {
  const allowed = ['GATHER', 'GATHER_STONE', 'HUNT', 'BUILD_SHELTER', 'BUILD_BOAT', 'EAT', 'ESCAPE'];
  assert.deepEqual(actionsAt({ x: 0, z: 0 }, allowed), ['BUILD_SHELTER', 'EAT']);
  assert.deepEqual(actionsAt({ x: -24, z: -15 }, allowed), ['GATHER', 'EAT']);
  assert.deepEqual(actionsAt({ x: 0, z: 62 }, allowed), ['BUILD_BOAT', 'EAT', 'ESCAPE']);
  assert.deepEqual(actionsAt({ x: 0, z: 0 }, ['ATTACK', 'DEFEND', 'FLEE']), ['ATTACK', 'DEFEND', 'FLEE']);
});

test('the follow camera projects the survivor in front of the viewer', () => {
  const matrix = cameraMatrix({ x: 0, z: 0 }, 0, 16 / 9);
  const projected = projectPoint(matrix, [0, 1, 0]);
  assert.ok(projected.visible);
  assert.ok(Math.abs(projected.x) < 0.001);
  assert.ok(Math.abs(projected.y) < 0.4);
  assert.equal(projectPoint(matrix, [0, 0, 100]).visible, false);
});

function worldFixture() {
  const frames = new Map();
  let frameNumber = 0;
  function element() {
    const listeners = new Map();
    const attributes = new Map();
    return { hidden: false, dataset: {}, style: {}, children: [], textContent: '',
      getContext() { return null; },
      addEventListener(type, listener) { listeners.set(type, listener); },
      removeEventListener(type) { listeners.delete(type); },
      replaceChildren(...children) { this.children = children; },
      setAttribute(name, value) { attributes.set(name, value); },
      getAttribute(name) { return attributes.get(name) ?? null; },
      removeAttribute(name) { attributes.delete(name); },
      focus() {}, setPointerCapture() {},
      emit(type, event) { listeners.get(type)?.(event); },
      listenerCount() { return listeners.size; },
    };
  }
  const nodes = new Map();
  const view = { ...element(), requestAnimationFrame(callback) { frames.set(++frameNumber, callback); return frameNumber; },
    cancelAnimationFrame(id) { frames.delete(id); } };
  const documentRoot = { ...element(), defaultView: view,
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id); },
    createElement: element,
  };
  documentRoot.getElementById('surviveJoin').hidden = true;
  const rendered = [];
  let disposals = 0;
  const renderer = { draw(position) { rendered.push({ ...position }); return { project: () => ({ visible: false, x: 0, y: 0 }) }; },
    dispose() { disposals++; } };
  return { documentRoot, view, frames, rendered, renderer, disposals: () => disposals,
    frame(timestamp) {
      const [id, callback] = frames.entries().next().value;
      frames.delete(id);
      callback(timestamp);
    },
  };
}
const worldState = { survivorId: 'alice', name: 'Alice', phase: 'DAY', status: 'EXPLORING', shelters: 0, boats: 0, actions: [] };

test('disposal aborts a stalled pulse, cancels animation and releases input and GPU resources', async () => {
  const fixture = worldFixture();
  let requestOptions;
  let completeRequest;
  const world = mountSurviveWorld(fixture.documentRoot, (_url, options) => {
    requestOptions = options;
    return new Promise(resolve => { completeRequest = resolve; });
  }, () => {}, () => fixture.renderer);
  world.setState(worldState);
  assert.equal(fixture.documentRoot.getElementById('surviveCampScene').getAttribute('hidden'), '');
  fixture.frame(0);
  assert.equal(requestOptions.signal.aborted, false);
  world.dispose();
  world.dispose();
  assert.equal(requestOptions.signal.aborted, true);
  assert.equal(fixture.frames.size, 0);
  assert.equal(fixture.disposals(), 1);
  assert.equal(fixture.view.listenerCount(), 0);
  completeRequest({ self: { survivorId: 'alice', x: 70, z: 0, heading: 0, sequence: 1 }, others: [], accepted: true });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(fixture.documentRoot.getElementById('surviveWorldLabels').children.length, 0);
});

test('ordinary snapshot refresh keeps destination walking active and no input applies before connection', async () => {
  const fixture = worldFixture();
  const world = mountSurviveWorld(fixture.documentRoot, async () => ({
    self: { survivorId: 'alice', x: 0, z: 0, heading: 0, sequence: 4 }, others: [], accepted: true,
  }), () => {}, () => fixture.renderer);
  world.setState(worldState);
  fixture.frame(0);
  await new Promise(resolve => setImmediate(resolve));
  fixture.documentRoot.getElementById('surviveWorld').emit('click', {
    target: { closest: selector => selector === '[data-destination]' ? { dataset: { destination: '1' } } : null },
  });
  fixture.frame(100);
  const first = fixture.rendered.at(-1).x;
  assert.ok(first < 0);
  world.setState({ ...worldState, revision: 2 });
  fixture.frame(200);
  assert.ok(fixture.rendered.at(-1).x < first);
  world.dispose();
});

test('unavailable graphics releases only the current survivor once for text fallback', async () => {
  const fixture = worldFixture();
  const requests = [];
  const world = mountSurviveWorld(fixture.documentRoot, async url => { requests.push(url); }, () => {}, () => { throw new Error('No WebGL'); });
  world.setState(worldState);
  world.setState(worldState);
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(requests, ['/api/survive/v1/movement/leave']);
  assert.equal(fixture.documentRoot.getElementById('surviveWorldCanvas').hidden, true);
  assert.equal(fixture.frames.size, 0);
});

test('a failed pulse removes remote avatars instead of displaying disconnected players indefinitely', async context => {
  let now = 1000;
  context.mock.method(Date, 'now', () => now);
  const fixture = worldFixture();
  let requests = 0;
  const world = mountSurviveWorld(fixture.documentRoot, async () => {
    if (++requests > 1) throw new Error('Offline');
    return { self: { survivorId: 'alice', x: 0, z: 0, heading: 0, sequence: 1 },
      others: [{ survivorId: 'bob', name: 'Bob', x: 1, z: 0, heading: 0 }], accepted: true };
  }, () => {}, () => fixture.renderer);
  world.setState(worldState);
  fixture.frame(0);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(fixture.documentRoot.getElementById('surviveWorldLabels').children.length, 6);
  now += 1000;
  fixture.frame(1000);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(fixture.documentRoot.getElementById('surviveWorldLabels').children.length, 5);
  world.dispose();
});

test('graphics loss cannot resurrect combat overlays or action notices after disposal', () => {
  const fixture = worldFixture();
  const world = mountSurviveWorld(fixture.documentRoot, async () => ({}), () => {}, () => fixture.renderer);
  world.setState({ ...worldState, revision: 1 });
  fixture.documentRoot.getElementById('surviveWorldCanvas').emit('webglcontextlost', { preventDefault() {} });
  world.setState({ ...worldState, revision: 2, status: 'COMBAT', enemy: 'HOG', enemyHealth: 5, health: 10 });
  world.beginAction('ATTACK');
  world.showError('The request failed.');
  assert.equal(fixture.documentRoot.getElementById('surviveWorldCombat').hidden, true);
  assert.equal(fixture.documentRoot.getElementById('surviveWorldNotice').hidden, true);
  assert.equal(fixture.frames.size, 0);
});

test('reduced motion suppresses reward particles while keeping confirmed gain text', () => {
  const fixture = worldFixture();
  fixture.view.matchMedia = () => ({ matches: true });
  let renderedEffect = 'not rendered';
  const originalDraw = fixture.renderer.draw;
  fixture.renderer.draw = (...args) => { renderedEffect = args[4]; return originalDraw(...args); };
  const world = mountSurviveWorld(fixture.documentRoot, async () => ({}), () => {}, () => fixture.renderer);
  world.setState({ ...worldState, revision: 1, wood: 0 });
  world.setState({ ...worldState, revision: 2, wood: 1, message: 'Gathered wood.' });
  fixture.frame(0);
  assert.equal(renderedEffect, null);
  assert.equal(fixture.documentRoot.getElementById('surviveWorldNotice').textContent, 'Gathered wood.');
  world.dispose();
});
