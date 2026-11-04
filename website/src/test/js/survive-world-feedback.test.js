import assert from 'node:assert/strict';
import test from 'node:test';
import { worldChangesBetween, createWorldFeedback } from '../../main/resources/static/js/survive-world-feedback.js';
import { combatPosition, cameraMatrix, projectPoint } from '../../main/resources/static/js/survive-world-model.js';

const before = { survivorId: 'alice', revision: 1, status: 'EXPLORING', wood: 0, stone: 0, food: 0,
  health: 10, hunger: 10, strength: 2, experience: 0, enemyHealth: 0, message: 'At camp.' };

test('confirmed gains become explicit resource and level labels without inventing XP at a level rollover', () => {
  const change = worldChangesBetween(before, { ...before, revision: 2, wood: 1, strength: 3, hunger: 9, message: 'Gathered wood.' });
  assert.equal(change.message, 'Gathered wood.');
  assert.equal(change.kind, 'gain');
  assert.ok(change.numbers.some(number => number.text === '+1 wood'));
  assert.ok(change.numbers.some(number => number.text === 'Level up!'));
  assert.equal(change.numbers.some(number => number.text.includes('XP')), false);
});

test('initial resume, repeated polls, stale revisions and replacement never announce fabricated gains', () => {
  assert.equal(worldChangesBetween(null, before), null);
  assert.equal(worldChangesBetween(before, { ...before, worldRevision: 300, wood: 4 }), null);
  assert.equal(worldChangesBetween(before, { ...before, revision: 0, food: 8 }), null);
  assert.equal(worldChangesBetween(before, { ...before, survivorId: 'bob', revision: 5, wood: 9 }), null);
});

test('combat damage distinguishes the player from the enemy and failed gathers still have an outcome', () => {
  const fighting = { ...before, status: 'COMBAT', enemy: 'HOG', enemyHealth: 5 };
  const hit = worldChangesBetween(fighting, { ...fighting, revision: 2, health: 9, enemyHealth: 2, message: 'You dealt 3 damage.' });
  assert.equal(hit.kind, 'damage');
  assert.ok(hit.numbers.some(number => number.text === '−1 health' && number.target === 'player'));
  assert.ok(hit.numbers.some(number => number.text === '−3 enemy health' && number.target === 'enemy'));
  assert.equal(worldChangesBetween(before, { ...before, revision: 2, message: 'No wood gathered.' }).message, 'No wood gathered.');
  const fled = worldChangesBetween(fighting, { ...before, revision: 2, health: 9, message: 'You fled from the hog to camp.' });
  assert.equal(fled.numbers.some(number => number.target === 'enemy'), false);
});

test('combat placement stays in front of the follow camera regardless of survivor heading', () => {
  for (const yaw of [0, Math.PI / 2, Math.PI, -Math.PI / 2]) {
    const position = { x: 2, z: 5, heading: yaw + Math.PI };
    const enemy = combatPosition(position, yaw);
    const projected = projectPoint(cameraMatrix(position, yaw, 16 / 9), [enemy.x, 1, enemy.z]);
    assert.equal(projected.visible, true);
    assert.ok(Math.abs(projected.x) < 0.01);
  }
});

function fixture() {
  const nodes = new Map();
  const make = () => ({ hidden: true, dataset: {}, style: {}, textContent: '', children: [],
    replaceChildren(...children) { this.children = children; }, setAttribute() {} });
  return { getElementById(id) { if (!nodes.has(id)) nodes.set(id, make()); return nodes.get(id); }, createElement: make };
}

test('combat remains visible on resume and transient outcomes expire without replay or interrupting combat', () => {
  const documentRoot = fixture();
  let now = 100;
  const feedback = createWorldFeedback(documentRoot, () => now);
  const fighting = { ...before, status: 'COMBAT', enemy: 'HOG', enemyHealth: 5 };
  feedback.acceptState(fighting);
  assert.equal(documentRoot.getElementById('surviveWorldCombat').hidden, false);
  assert.match(documentRoot.getElementById('surviveWorldEnemyName').textContent, /hog/i);
  feedback.acceptState({ ...fighting, revision: 2, health: 9, message: 'The hog bit you.' });
  assert.equal(documentRoot.getElementById('surviveWorldNotice').hidden, false);
  now += 7000;
  feedback.draw(() => ({ x: 0, y: 0, visible: true }), { x: 0, z: 0 }, { x: 0, z: -3 });
  assert.equal(documentRoot.getElementById('surviveWorldNotice').hidden, true);
  assert.equal(documentRoot.getElementById('surviveWorldCombat').hidden, false);
  feedback.acceptState({ ...fighting, revision: 2, health: 9 });
  assert.equal(documentRoot.getElementById('surviveWorldNotice').hidden, true);
  feedback.acceptState({ ...before, survivorId: 'bob' });
  assert.equal(documentRoot.getElementById('surviveWorldCombat').hidden, true);
  assert.equal(documentRoot.getElementById('surviveWorldNumbers').children.length, 0);
});

test('pending and failed actions show text but never award resources', () => {
  const documentRoot = fixture();
  const feedback = createWorldFeedback(documentRoot);
  feedback.acceptState(before);
  feedback.beginAction('GATHER');
  feedback.acceptState(before, true);
  assert.equal(documentRoot.getElementById('surviveWorldNotice').textContent, 'Gather wood…');
  assert.equal(documentRoot.getElementById('surviveWorldNumbers').children.length, 0);
  feedback.showError('<img src=x> Action could not be confirmed.');
  feedback.acceptState(before, false);
  assert.equal(documentRoot.getElementById('surviveWorldNotice').textContent, '<img src=x> Action could not be confirmed.');
  assert.equal(documentRoot.getElementById('surviveWorldNotice').hidden, false);
  assert.equal(documentRoot.getElementById('surviveWorldNumbers').children.length, 0);
  feedback.dispose();
  assert.equal(documentRoot.getElementById('surviveWorldNotice').hidden, true);
});

test('multiple reward labels occupy separate screen lines even when their world projection is identical', () => {
  const documentRoot = fixture();
  const feedback = createWorldFeedback(documentRoot);
  feedback.acceptState(before);
  feedback.acceptState({ ...before, revision: 2, wood: 1, hunger: 9, strength: 3 });
  feedback.draw(() => ({ x: 0, y: 0, visible: true }), { x: 0, z: 0 }, { x: 0, z: -3 });
  const labels = documentRoot.getElementById('surviveWorldNumbers').children;
  assert.equal(labels.length, 3);
  assert.equal(new Set(labels.map(label => label.style.marginTop)).size, 3);
});
