import assert from 'node:assert/strict';
import test from 'node:test';
import { groundHeight, islandMesh, meshBuilder } from '../../main/resources/static/js/survive-world-art.js';
import { cameraMatrix, LANDMARKS, projectPoint } from '../../main/resources/static/js/survive-world-model.js';
import { actionPose } from '../../main/resources/static/js/survive-action-pose.js';

test('the rendered island has finite geometry within its static triangle budget', () => {
  const { vertices } = islandMesh();
  assert.equal(vertices.length % 18, 0);
  assert.ok(vertices.length / 18 > 15000 && vertices.length / 18 < 50000);
  assert.ok(vertices.every(Number.isFinite));
  assert.ok(groundHeight(34, -19) > groundHeight(0, 0) + 2);
  assert.ok(groundHeight(78, 0) < groundHeight(62, 0));
});

test('confirmed harvest, fishing and combat actions create distinct animated meshes', () => {
  const meshes = ['GATHER', 'GATHER_STONE', 'FISH', 'ATTACK', 'CRAFT_AXE', 'WAKE'].map(action => {
    const mesh = meshBuilder();
    mesh.avatar(0, 0, 0, [0.55, 0.41, 0.27], 'EXPLORING', 0, null, actionPose({ action, ageSeconds: 0.45 }));
    assert.ok(mesh.vertices.every(Number.isFinite));
    return mesh.vertices;
  });
  for (let index = 1; index < meshes.length; index++) assert.notDeepEqual(meshes[index - 1], meshes[index]);
  assert.equal(actionPose({ action: 'FISH', ageSeconds: 3 }), null);
});

test('adventurer gait changes geometry and the follow camera anchors to each landmark height', () => {
  const color = [0.55, 0.41, 0.27];
  const standing = meshBuilder();
  const walking = meshBuilder();
  standing.avatar(30, -12, 0, color, 'EXPLORING', 0, 'SPEAR');
  walking.avatar(30, -12, 0, color, 'EXPLORING', 1, 'SPEAR');
  assert.equal(walking.vertices.length, standing.vertices.length);
  assert.notDeepEqual(walking.vertices, standing.vertices);
  for (const landmark of LANDMARKS) {
    const elevation = groundHeight(landmark.x, landmark.z);
    const matrix = cameraMatrix(landmark, 0, 16 / 9, elevation);
    assert.equal(projectPoint(matrix, [landmark.x, elevation + 2.5, landmark.z]).visible, true);
  }
});
