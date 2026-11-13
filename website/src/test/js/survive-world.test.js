import assert from 'node:assert/strict';
import test from 'node:test';
import { advancePosition, actionsAt, cameraMatrix, enemyFacing, LANDMARKS, projectPoint, targetActionsFor } from '../../main/resources/static/js/survive-world-model.js';

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

test('fishing and hunting require the bank or a nearby roaming animal', () => {
  assert.deepEqual(actionsAt({ x: -13, z: 70 }, ['FISH']), ['FISH']);
  assert.deepEqual(actionsAt({ x: 0, z: 0 }, ['FISH']), []);
  const hog = { x: -34, z: 24 };
  assert.deepEqual(actionsAt({ x: -30, z: 24 }, ['HUNT'], [hog]), ['HUNT']);
  assert.deepEqual(actionsAt({ x: -30, z: 24 }, ['HUNT'], [{ ...hog, claimed: true }]), []);
  assert.deepEqual(actionsAt({ x: -28, z: 24 }, ['HUNT'], [hog]), []);
});

test('the follow camera projects the survivor in front of the viewer', () => {
  const matrix = cameraMatrix({ x: 0, z: 0 }, 0, 16 / 9);
  const projected = projectPoint(matrix, [0, 1, 0]);
  assert.ok(projected.visible);
  assert.ok(Math.abs(projected.x) < 0.001);
  assert.ok(Math.abs(projected.y) < 0.4);
  assert.equal(projectPoint(matrix, [0, 0, 100]).visible, false);
});

test('desktop framing lowers the distant ground and zoom keeps the character in view', () => {
  const position = { x: 0, z: 0 };
  const defaultView = cameraMatrix(position, 0, 16 / 9);
  assert.ok(projectPoint(defaultView, [0, 0, -35]).y < 0.24);
  const closer = cameraMatrix(position, 0, 16 / 9, 0, 0.72);
  const farther = cameraMatrix(position, 0, 16 / 9, 0, 1.45);
  const head = [0, 2.58, 0];
  const feet = [0, 0, 0];
  assert.equal(projectPoint(closer, head).visible, true);
  assert.equal(projectPoint(closer, feet).visible, true);
  assert.ok(projectPoint(closer, head).y - projectPoint(closer, feet).y
    > projectPoint(farther, head).y - projectPoint(farther, feet).y);
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
  const renderer = { draw(position, yaw, _state, _others, _time, _wildlife, _enemy, gesture, zoom, counter) {
    rendered.push({ ...position, yaw, zoom, gesture, counter });
    return { project: () => ({ visible: false, x: 0, y: 0 }) };
  },
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

test('presence reconciliation starts one remote recoil and keeps its receipt time on repeated health', async context => {
  let now = 1000;
  context.mock.method(Date, 'now', () => now);
  context.mock.method(performance, 'now', () => now);
  const fixture = worldFixture();
  let health = 10;
  let drawn;
  const world = mountSurviveWorld(fixture.documentRoot, async () => ({
    self: { survivorId: 'alice', x: 0, z: 0, heading: 0, sequence: 1 }, accepted: true,
    others: [{ survivorId: 'bob', name: 'Bob', x: 1, z: 0, heading: 0, status: 'COMBAT', appearance: { health } }],
  }), () => {}, () => ({
    draw(_position, _yaw, _state, others) {
      drawn = others;
      return { project: () => ({ visible: false }) };
    }, dispose() {},
  }));
  try {
    world.setState(worldState);
    fixture.frame(now);
    await new Promise(resolve => setImmediate(resolve));
    fixture.frame(now + 16);
    assert.equal(drawn[0].hitAtSeconds, undefined);
    health = 9;
    now = 2000;
    fixture.frame(now);
    await new Promise(resolve => setImmediate(resolve));
    fixture.frame(now + 16);
    assert.equal(drawn[0].hitAtSeconds, 2);
    now = 3000;
    fixture.frame(now);
    await new Promise(resolve => setImmediate(resolve));
    fixture.frame(now + 16);
    assert.equal(drawn[0].hitAtSeconds, 2);
  } finally { world.dispose(); }
});

test('an ally hit does not animate the local fighter, while their own confirmed strike does', async () => {
  const fixture = worldFixture();
  const world = mountSurviveWorld(fixture.documentRoot, async () => ({
    self: { survivorId: 'alice', x: 0, z: 0, heading: 0, sequence: 1 },
    others: [], accepted: true, wildlife: [],
  }), () => {}, () => fixture.renderer);
  const engaged = { ...worldState, status: 'COMBAT', enemy: 'BEAR', enemyHealth: 10,
    nextStrikeAt: '2026-11-05T14:00:00Z', revision: 1, health: 10, defending: true, strike: null };
  try {
    world.setState(engaged);
    fixture.frame(0);
    await new Promise(resolve => setImmediate(resolve));
    world.setState({ ...engaged, revision: 2, enemyHealth: 7, defending: false });
    fixture.frame(16);
    assert.equal(fixture.rendered.at(-1).gesture, null);
    assert.equal(fixture.rendered.at(-1).counter.kind, 'strike');
    world.setState({ ...engaged, revision: 3, enemyHealth: 5,
      strike: { revision: 3, weaponSkill: true, weapon: 'CLUB' } });
    fixture.frame(32);
    assert.equal(fixture.rendered.at(-1).gesture.action, 'WEAPON_SKILL');
  } finally { world.dispose(); }
});

test('zoom resets and a locked camera tracks the acknowledged enemy without orbit input', async () => {
  const fixture = worldFixture();
  const world = mountSurviveWorld(fixture.documentRoot, async () => ({
    self: { survivorId: 'alice', x: 0, z: 0, heading: 0, sequence: 1, status: 'COMBAT' },
    others: [], accepted: true, encounter: { id: 'wolf', x: -4, z: -4 },
  }), () => {}, () => fixture.renderer);
  const canvas = fixture.documentRoot.getElementById('surviveWorldCanvas');
  const press = code => canvas.emit('keydown', { code, preventDefault() {} });
  try {
    world.setState({ ...worldState, status: 'COMBAT', enemy: 'WOLF', enemyHealth: 8 });
    fixture.frame(0);
    await new Promise(resolve => setImmediate(resolve));
    press('PageUp');
    press('NumpadMultiply');
    press('KeyR');
    fixture.frame(16);
    assert.equal(fixture.rendered.at(-1).zoom, 0.87);
    assert.equal(fixture.rendered.at(-1).yaw, Math.PI / 4);
    press('Home');
    fixture.frame(32);
    assert.equal(fixture.rendered.at(-1).zoom, 1);
    assert.equal(fixture.rendered.at(-1).yaw, 0);
  } finally { world.dispose(); }
});

test('field targeting sends the selected creature ID after a fresh movement pulse', async () => {
  const fixture = worldFixture();
  const sent = [];
  const wildlife = [
    { id: 'nearest-hog', kind: 'HOG', x: 1, z: 0 },
    { id: 'selected-hog', kind: 'HOG', x: 3, z: 0 },
  ];
  const world = mountSurviveWorld(fixture.documentRoot, async () => ({
    self: { survivorId: 'alice', x: 0, z: 0, heading: 0, sequence: 1 },
    others: [], accepted: true, wildlife,
  }), (...action) => sent.push(action), () => ({
    draw: () => ({ project: () => ({ visible: true, x: 0, y: 0 }) }), dispose() {},
  }));
  const byId = id => fixture.documentRoot.getElementById(id);
  const commands = byId('surviveWorldActions');
  const canvas = byId('surviveWorldCanvas');
  const press = code => canvas.emit('keydown', { code, preventDefault() {} });
  try {
    commands.hidden = true;
    world.setState({ ...worldState, actions: ['HUNT'] });
    fixture.frame(0);
    await new Promise(resolve => setImmediate(resolve));
    fixture.frame(16);
    press('Tab');
    press('Tab');
    press('Enter');
    assert.deepEqual(commands.children.map(button => button.textContent), ['Check', 'Engage']);
    commands.emit('click', { target: { closest: () => commands.children[1] } });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(sent, [['ENGAGE', 'selected-hog']]);
  } finally { world.dispose(); }
});

test('selecting an animal opens commands and Escape returns to the island', () => {
  const { documentRoot, renderer } = worldFixture();
  const actions = [];
  const world = mountSurviveWorld(documentRoot, async () => {}, action => actions.push(action), () => renderer);
  const byId = id => documentRoot.getElementById(id);
  try {
    byId('surviveWorldActions').hidden = true;
    world.setState({ ...worldState, status: 'COMBAT', enemy: 'HOG', enemyHealth: 5, actions: ['ENGAGE', 'FLEE'] });
    byId('surviveWorldCanvas').emit('keydown', { code: 'Enter', preventDefault() {} });
    assert.equal(byId('surviveWorldEnemyMarker').getAttribute('aria-pressed'), 'true');
    assert.equal(byId('surviveCommandButton').getAttribute('aria-expanded'), 'true');
    assert.deepEqual(byId('surviveWorldActions').children.map(button => button.textContent), ['Check', 'Engage', 'Flee to camp']);
    assert.deepEqual(actions, []);
    byId('surviveWorld').emit('keydown', { code: 'Escape', target: { closest: () => null }, preventDefault() {} });
    assert.equal(byId('surviveWorldActions').hidden, true);
    assert.equal(byId('surviveCommandButton').getAttribute('aria-expanded'), 'false');
  } finally { world.dispose(); }
});

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

test('ordinary snapshots preserve held keyboard movement and update the map marker', async () => {
  const fixture = worldFixture();
  const world = mountSurviveWorld(fixture.documentRoot, async () => ({
    self: { survivorId: 'alice', x: 0, z: 0, heading: 0, sequence: 4 }, others: [], accepted: true,
  }), () => {}, () => fixture.renderer);
  world.setState(worldState);
  fixture.frame(0);
  await new Promise(resolve => setImmediate(resolve));
  fixture.documentRoot.getElementById('surviveWorldCanvas').emit('keydown', { code: 'KeyA', preventDefault() {} });
  fixture.frame(100);
  const first = fixture.rendered.at(-1).x;
  assert.ok(first < 0);
  world.setState({ ...worldState, revision: 2 });
  fixture.documentRoot.getElementById('surviveMapSection').open = true;
  fixture.frame(200);
  assert.ok(fixture.rendered.at(-1).x < first);
  assert.match(fixture.documentRoot.getElementById('surviveMapPlayer').getAttribute('transform'), /translate\(-/);
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
  assert.equal(fixture.documentRoot.getElementById('surviveWorldLabels').children.length, LANDMARKS.length + 1);
  now += 1000;
  fixture.frame(1000);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(fixture.documentRoot.getElementById('surviveWorldLabels').children.length, LANDMARKS.length);
  world.dispose();
});

test('graphics loss cannot resurrect the target HUD after disposal', () => {
  const fixture = worldFixture();
  const world = mountSurviveWorld(fixture.documentRoot, async () => ({}), () => {}, () => fixture.renderer);
  world.setState({ ...worldState, revision: 1 });
  fixture.documentRoot.getElementById('surviveWorldCanvas').emit('webglcontextlost', { preventDefault() {} });
  world.setState({ ...worldState, revision: 2, status: 'COMBAT', enemy: 'HOG', enemyHealth: 5, health: 10 });
  assert.equal(fixture.documentRoot.getElementById('surviveWorldCombat').hidden, true);
  assert.equal(fixture.frames.size, 0);
});

test('Escape closes the weapon picker, then Equipment, then Main Menu', () => {
  const fixture = worldFixture();
  const world = mountSurviveWorld(fixture.documentRoot, async () => ({}), () => {}, () => fixture.renderer);
  const shell = fixture.documentRoot.getElementById('surviveWorld');
  const mainMenu = fixture.documentRoot.getElementById('surviveMainMenu');
  const canvas = fixture.documentRoot.getElementById('surviveWorldCanvas');
  const equipment = { open: true, parentElement: { closest: () => mainMenu } };
  const picker = { open: true, parentElement: { closest: () => equipment } };
  const menuPanels = [equipment, picker];
  let focused;
  for (const panel of menuPanels) {
    panel.querySelector = () => ({ focus: () => { focused = panel; } });
  }
  mainMenu.open = true;
  mainMenu.contains = panel => menuPanels.includes(panel);
  canvas.focus = () => { focused = canvas; };
  const cancel = panel => shell.emit('keydown', { code: 'Escape', preventDefault() {},
    target: { closest: selector => selector === 'details[open]' ? (panel.open ? panel : panel.parentElement.closest()) : null },
  });
  try {
    cancel(picker);
    assert.equal(picker.open, false);
    assert.equal(equipment.open, true);
    assert.equal(mainMenu.open, true);
    assert.equal(focused, picker);
    cancel(picker);
    assert.equal(equipment.open, false);
    assert.equal(mainMenu.open, true);
    assert.equal(focused, equipment);
    cancel(mainMenu);
    assert.equal(mainMenu.open, false);
    assert.equal(focused, canvas);
  } finally {
    world.dispose();
  }
});

test('Arrow Down moves from the Main weapon row to an available weapon choice', () => {
  const fixture = worldFixture();
  const world = mountSurviveWorld(fixture.documentRoot, async () => ({}), () => {}, () => fixture.renderer);
  const shell = fixture.documentRoot.getElementById('surviveWorld');
  const disabled = { disabled: true, getClientRects: () => [{}] };
  let focused;
  const weapon = { getClientRects: () => [{}], focus: () => { focused = weapon; } };
  const main = { getClientRects: () => [{}], closest: selector => selector === '.survive-main-menu-panel' ? panel : null };
  const panel = { querySelectorAll: () => [main, disabled, weapon] };
  try {
    shell.emit('keydown', { code: 'ArrowDown', target: main, preventDefault() {} });
    assert.equal(focused, weapon);
  } finally {
    world.dispose();
  }
});

test('shared enemies face the server-selected survivor by identity', () => {
  const enemy = { x: 0, z: 0, heading: Math.PI };
  const local = { x: 0, z: 4 };
  const others = [{ survivorId: 'other', x: 4, z: 0 }, { survivorId: 'guard', x: -4, z: 0 }];
  assert.equal(enemyFacing(enemy, local, others, { self: false, survivorId: 'guard' }), -Math.PI / 2);
  assert.equal(enemyFacing(enemy, local, [], { self: false, survivorId: 'guard' }), Math.PI);
  assert.equal(enemyFacing(enemy, local, others, { self: true, survivorId: 'scout' }), 0);
  assert.equal(enemyFacing(enemy, local, others, null), 0);
});

test('a shared enemy stays at its acknowledged position as the local fighter engages', async () => {
  const fixture = worldFixture();
  const encounter = { id: 'ridge-bear', x: -4, z: -4, claimed: true };
  const drawn = [];
  const world = mountSurviveWorld(fixture.documentRoot, async () => ({
    self: { survivorId: 'alice', x: 0, z: 0, heading: 0, sequence: 1, status: 'COMBAT' },
    others: [], accepted: true, encounter,
  }), () => {}, () => ({
    ...fixture.renderer,
    draw(_position, _yaw, _state, _others, _time, _wildlife, enemy) {
      drawn.push(enemy);
      return { project: () => ({ visible: false }) };
    },
  }));
  try {
    world.setState({ ...worldState, status: 'COMBAT', enemy: 'BEAR', nextStrikeAt: '2026-11-05T14:00:00Z' });
    fixture.frame(0);
    await new Promise(resolve => setImmediate(resolve));
    fixture.frame(16);
    fixture.frame(1800);
    assert.deepEqual(drawn.at(-1), encounter);
  } finally {
    world.dispose();
  }
});
