import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { prepareActorArt } from '../../main/resources/static/js/survive-authored-actors.js';
import { groundHeight, islandMesh, meshBuilder, MESH_VERTEX_FLOATS } from '../../main/resources/static/js/survive-world-art.js';
import { cameraMatrix, LANDMARKS, projectPoint } from '../../main/resources/static/js/survive-world-model.js';
import { actionPose } from '../../main/resources/static/js/survive-action-pose.js';
import { drawBoat, drawShelter } from '../../main/resources/static/js/survive-world-props.js';

test('authored actors draw bounded texture coordinates and animate with the equipped tool', () => {
  const asset = kind => JSON.parse(readFileSync(new URL(`../../main/resources/static/images/survive/${kind}.json`, import.meta.url), 'utf8'));
  const actorArt = {
    adventurer: prepareActorArt(asset('adventurer')),
    boar: prepareActorArt(asset('boar')),
    wolf: prepareActorArt(asset('wolf')),
    bear: prepareActorArt(asset('bear')),
  };
  for (const model of Object.values(actorArt)) {
    assert.notDeepEqual(model.poses.walk[0], model.poses.walk[3]);
  }
  assert.notDeepEqual(actorArt.adventurer.poses.strike[0], actorArt.adventurer.poses.strike[5]);
  const scene = (seconds, action) => {
    const mesh = meshBuilder(actorArt);
    mesh.avatar(8, 9, 0.4, [0.24, 0.31, 0.42], 'EXPLORING', 1, 'AXE', action, true, seconds);
    mesh.animal(20, -15, 'HOG', 1, 0.7, 1, 0, false, seconds);
    mesh.animal(22, -15, 'WOLF', 1, 0.7, 1, 0, false, seconds);
    mesh.animal(24, -15, 'BEAR', 1.6, 0.7, 1, 0, false, seconds);
    return mesh.vertices;
  };
  const walking = scene(0.2);
  const mining = scene(0.2, actionPose({ action: 'GATHER_STONE', ageSeconds: 0.6 }));
  assert.notDeepEqual(walking, scene(0.36));
  assert.notDeepEqual(walking, mining);
  assert.ok(mining.every(Number.isFinite));
  let texturedCorners = 0;
  for (let index = 6; index < mining.length; index += MESH_VERTEX_FLOATS) {
    if (mining[index] !== 12) continue;
    texturedCorners++;
    assert.ok(mining[index + 1] >= 0 && mining[index + 1] <= 1);
    assert.ok(mining[index + 2] >= 0 && mining[index + 2] <= 1);
  }
  assert.equal(texturedCorners, (2151 + 1144 + 1348 + 929) * 3);
});

test('the rendered island has finite geometry within its static triangle budget', () => {
  const { vertices } = islandMesh();
  const triangleSize = MESH_VERTEX_FLOATS * 3;
  assert.equal(vertices.length % triangleSize, 0);
  assert.ok(vertices.length / triangleSize > 15000 && vertices.length / triangleSize < 55000);
  assert.ok(vertices.every(Number.isFinite));
  assert.ok(groundHeight(34, -19) > groundHeight(0, 0) + 2);
  assert.ok(groundHeight(78, 0) < groundHeight(62, 0));
});

test('terrain, ruin masonry, bark and cutout foliage are represented in the same render mesh', () => {
  const { vertices } = islandMesh();
  const materials = new Set();
  for (let index = 6; index < vertices.length; index += MESH_VERTEX_FLOATS) materials.add(vertices[index]);
  assert.ok(materials.has(1));
  assert.ok(materials.has(1.9));
  assert.ok(materials.has(2.5));
  assert.ok(materials.has(3.5));
  assert.ok(materials.has(4.5));
});

test('the gate route has raised banks and a walkable bridge over rendered water', () => {
  const { vertices } = islandMesh();
  assert.ok(groundHeight(-8, -13) > groundHeight(0, -13) + 2.2);
  assert.ok(groundHeight(0, -26) > groundHeight(4, -26) + 0.8);
  const materials = vertices.filter((_, index) => index % MESH_VERTEX_FLOATS === 6);
  assert.ok(materials.includes(13.5));
});

test('animated adventurers and each animal use bounded local texture coordinates', () => {
  const meshes = ['HOG', 'WOLF', 'BEAR'].map(kind => {
    const mesh = meshBuilder();
    mesh.animal(20, -15, kind, 1, 0.7, 0.4);
    return mesh.vertices;
  });
  assert.notDeepEqual(meshes[0], meshes[1]);
  assert.notDeepEqual(meshes[1], meshes[2]);
  const avatar = meshBuilder();
  avatar.avatar(8, 9, 0.4, [0.24, 0.31, 0.42], 'COMBAT', 0, 'SPEAR', actionPose({ action: 'ATTACK', ageSeconds: 0.4 }));
  meshes.push(avatar.vertices);
  const materials = new Set();
  for (const mesh of meshes) {
    assert.ok(mesh.length / (MESH_VERTEX_FLOATS * 3) < 2500);
    for (let index = 6; index < mesh.length; index += MESH_VERTEX_FLOATS) {
      if (mesh[index] < 5 || mesh[index] > 9) continue;
      materials.add(mesh[index]);
      assert.ok(mesh[index + 1] >= 0 && mesh[index + 1] <= 1);
      assert.ok(mesh[index + 2] >= 0 && mesh[index + 2] <= 1);
    }
  }
  assert.deepEqual([...materials].sort(), [5.5, 6.5, 7.5, 8.5]);
});

test('constructed shelters and boats emit finite, textured geometry', () => {
  const shelter = meshBuilder();
  const boat = meshBuilder();
  drawShelter(shelter, 0, 1, 0);
  drawBoat(boat, 0, 80);
  for (const { vertices } of [shelter, boat]) {
    assert.ok(vertices.length > MESH_VERTEX_FLOATS * 100);
    assert.ok(vertices.every(Number.isFinite));
  }
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
