import assert from 'node:assert/strict';
import test from 'node:test';
import { createWorldFeedback } from '../../main/resources/static/js/survive-world-feedback.js';
import { combatPosition, cameraMatrix, projectPoint } from '../../main/resources/static/js/survive-world-model.js';

test('combat placement stays in front of the follow camera regardless of survivor heading', () => {
  for (const yaw of [0, Math.PI / 2, Math.PI, -Math.PI / 2]) {
    const position = { x: 2, z: 5, heading: yaw + Math.PI };
    const enemy = combatPosition(position, yaw);
    const projected = projectPoint(cameraMatrix(position, yaw, 16 / 9), [enemy.x, 1, enemy.z]);
    assert.equal(projected.visible, true);
    assert.ok(projected.x > 0.1 && projected.x < 0.5, 'enemy must be visible beside the survivor silhouette');
  }
});

test('the combat HUD shows current target state and clears when the encounter ends', () => {
  const nodes = new Map();
  const documentRoot = { getElementById(id) {
    if (!nodes.has(id)) nodes.set(id, { hidden: true, style: {}, textContent: '' });
    return nodes.get(id);
  } };
  const feedback = createWorldFeedback(documentRoot);
  const combat = documentRoot.getElementById('surviveWorldCombat');
  const marker = documentRoot.getElementById('surviveWorldEnemyMarker');
  feedback.acceptState({ status: 'COMBAT', enemy: 'HOG', enemyHealth: 3, nextStrikeAt: 'scheduled' });
  assert.equal(combat.hidden, false);
  assert.equal(marker.textContent, 'Wild hog · 3 health');
  assert.equal(documentRoot.getElementById('surviveWorldCombatTitle').textContent, 'In combat');
  assert.equal(documentRoot.getElementById('surviveWorldEnemyMeter').value, 3);
  feedback.draw(() => ({ x: 0, y: 0, visible: true }), { x: 0, z: -3 });
  assert.equal(marker.style.left, '50%');
  feedback.acceptState({ status: 'EXPLORING' });
  assert.equal(combat.hidden, true);
  assert.equal(marker.hidden, true);
});
