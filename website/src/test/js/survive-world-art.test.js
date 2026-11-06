import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { prepareActorArt } from '../../main/resources/static/js/survive-authored-actors.js';
import { createDynamicMesh } from '../../main/resources/static/js/survive-dynamic-mesh.js';
import { groundHeight, islandMesh, meshBuilder, MESH_VERTEX_FLOATS } from '../../main/resources/static/js/survive-world-art.js';
import { cameraMatrix, LANDMARKS, projectPoint } from '../../main/resources/static/js/survive-world-model.js';
import { actionPose, enemyCountered, enemyCounterPose, enemyDefeated, enemyFallPose } from '../../main/resources/static/js/survive-action-pose.js';
import { drawBoat, drawCampFlame, drawShelter } from '../../main/resources/static/js/survive-world-props.js';

test('dynamic frames upload the same vertices while reusing their allocated capacity', () => {
  const allocations = [];
  const uploads = [];
  const gl = {
    bindBuffer() {},
    bufferData(_target, bytes) { allocations.push(bytes); },
    bufferSubData(_target, _offset, vertices) { uploads.push(Array.from(vertices)); },
  };
  const dynamic = createDynamicMesh(gl, {});
  const streamed = meshBuilder(undefined, dynamic.append);
  const ordinary = meshBuilder();
  const draw = mesh => mesh.triangle([0, 0, 0], [0, 1, 0], [1, 0, 0], [0.3, 0.5, 0.7]);
  draw(ordinary);
  for (let frame = 0; frame < 2; frame++) {
    dynamic.reset();
    draw(streamed);
    assert.equal(dynamic.upload(), ordinary.vertices.length);
  }
  assert.equal(allocations.length, 1);
  const expected = Array.from(new Float32Array(ordinary.vertices));
  assert.deepEqual(uploads, [expected, expected]);
});

test('authored actors draw bounded texture coordinates and animate with the equipped tool', () => {
  const asset = kind => JSON.parse(readFileSync(new URL(`../../main/resources/static/images/survive/${kind}.json`, import.meta.url), 'utf8'));
  const actorArt = {
    adventurer: prepareActorArt(asset('adventurer'), { adventurer: true }),
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

test('the adventurer reshaping keeps clip topology and hand attachments', () => {
  const raw = JSON.parse(readFileSync(new URL('../../main/resources/static/images/survive/adventurer.json', import.meta.url), 'utf8'));
  const baseline = prepareActorArt(raw);
  const refined = prepareActorArt(raw, { adventurer: true });
  assert.deepEqual(refined.triangles.map(({ indices, uv }) => ({ indices, uv })), baseline.triangles);
  for (const [clip, frames] of Object.entries(refined.poses)) {
    assert.equal(frames.length, baseline.poses[clip].length);
    assert.equal(frames[0].length, baseline.poses[clip][0].length);
  }
  const feet = baseline.poses.idle[0].findIndex((value, index) => index % 3 === 1 && value === 0);
  assert.ok(feet >= 0);
  assert.equal(refined.poses.idle[0][feet], 0);
  assert.ok(refined.hands.strike[0][1] > baseline.hands.strike[0][1]);
});

test('authored lighting interpolates across triangle corners without adding vertices', () => {
  const mesh = meshBuilder();
  mesh.triangle([0, 0, 0], [0, 1, 0], [1, 0, 0], [1, 1, 1], 12,
    [[0, 0], [0, 1], [1, 0]], [0.76, 0.89, 1]);
  assert.equal(mesh.vertices.length, 3 * MESH_VERTEX_FLOATS);
  assert.deepEqual(mesh.vertices.filter((_, index) => index % MESH_VERTEX_FLOATS === 3), [0.76, 0.89, 1]);

  const raw = {
    faces: [0, 1, 2, 1, 0, 3, 0, 2, 4],
    uv: Array(18).fill(0),
    poses: { idle: [[0, 0, 0, 1000, 0, 0, 0, 0, 1000, 0, 500, -1000, 0, 1000, 0]] },
    hands: {},
  };
  const [flat, tilted, crease] = prepareActorArt(raw, { adventurer: true }).triangles;
  assert.equal(flat.shades[0], tilted.shades[1]);
  assert.equal(flat.shades[1], tilted.shades[0]);
  assert.notEqual(flat.shades[0], crease.shades[0]);
});

test('enemy motion requires a confirmed bite or guard and winds back before lunging', () => {
  const previous = { status: 'COMBAT', survivorId: 'scout', enemy: 'WOLF', revision: 4, health: 8, enemyHealth: 6 };
  const afterStrike = { ...previous, revision: 5, enemyHealth: 4 };
  assert.equal(enemyCountered(previous, afterStrike), false);
  assert.equal(enemyCountered(previous, { ...afterStrike, health: 6 }), true);
  assert.equal(enemyCountered({ ...previous, defending: true }, { ...afterStrike, defending: false }), true);
  assert.equal(enemyCountered(previous, { ...afterStrike, survivorId: 'other', health: 6 }), false);
  assert.ok(enemyCounterPose(0.11).forward < 0);
  assert.ok(enemyCounterPose(0.43).forward > 0);
  assert.equal(enemyCounterPose(0.43).recoil, 0);
  assert.equal(enemyCounterPose(0.11, true).recoil, 0);
  assert.ok(enemyCounterPose(0.43, true).recoil > 0.8);
  assert.equal(enemyCounterPose(0.76), null);
});

test('a landed bite moves the authored torso without sliding the feet', () => {
  const raw = JSON.parse(readFileSync(new URL('../../main/resources/static/images/survive/adventurer.json', import.meta.url), 'utf8'));
  const actorArt = { adventurer: prepareActorArt(raw, { adventurer: true }) };
  const draw = recoil => {
    const mesh = meshBuilder(actorArt);
    mesh.avatar(0, 0, 0, [0.24, 0.31, 0.42], 'COMBAT', 0, 'CLUB',
      { kind: 'ready', progress: 0.5, weight: 0.75, recoil });
    return mesh.vertices;
  };
  const ready = draw(0);
  const hit = draw(1);
  let torsoRetreat = false;
  for (let index = 0; index < ready.length; index += MESH_VERTEX_FLOATS) {
    if (ready[index + 1] < 0.5) assert.deepEqual(hit.slice(index, index + 3), ready.slice(index, index + 3));
    if (ready[index + 1] > 1.9 && hit[index + 2] < ready[index + 2] - 0.2) torsoRetreat = true;
  }
  assert.ok(torsoRetreat);
});

test('confirmed personal and party wins leave a short fall without animating flee or defeat', () => {
  const fighting = { survivorId: 'scout', status: 'COMBAT', revision: 9 };
  const exit = { survivorId: 'scout', status: 'EXPLORING', revision: 10 };
  assert.equal(enemyDefeated(fighting, { ...exit, combatOutcome: 'You defeated the hog.' }), true);
  assert.equal(enemyDefeated(fighting, { ...exit, combatOutcome: 'Your party won the fight. You gained 1 food.' }), true);
  assert.equal(enemyDefeated(fighting, { ...exit, combatOutcome: 'You fled from the hog.' }), false);
  assert.equal(enemyDefeated(fighting, { ...exit, status: 'DEAD' }), false);
  assert.equal(enemyDefeated(fighting, { ...exit, survivorId: 'other', combatOutcome: 'You defeated the hog.' }), false);
  assert.equal(enemyFallPose(0.18).weight, 0);
  assert.ok(enemyFallPose(0.61).weight > 0.5);
  assert.equal(enemyFallPose(0.8).weight, 1);
  assert.ok(enemyFallPose(1.02).sink > 0.5);
  assert.equal(enemyFallPose(1.18), null);
});

test('authored wildlife rolls to the ground without changing its texture coordinates', () => {
  const raw = JSON.parse(readFileSync(new URL('../../main/resources/static/images/survive/boar.json', import.meta.url), 'utf8'));
  const draw = fall => {
    const mesh = meshBuilder({ boar: prepareActorArt(raw) });
    mesh.animal(0, 0, 'HOG', 1, 0, 0, 0, true, 0, fall);
    return mesh.vertices.filter((_, index, vertices) => vertices[index - index % MESH_VERTEX_FLOATS + 6] === 12);
  };
  const standing = draw(enemyFallPose(0));
  const fallen = draw(enemyFallPose(0.8));
  const heights = mesh => mesh.filter((_, index) => index % MESH_VERTEX_FLOATS === 1);
  assert.ok(Math.max(...heights(fallen)) < Math.max(...heights(standing)) * 0.7);
  assert.deepEqual(fallen.filter((_, index) => index % MESH_VERTEX_FLOATS > 6),
    standing.filter((_, index) => index % MESH_VERTEX_FLOATS > 6));
});

test('an engaged stance moves the equipped spear from the back to the hand', () => {
  const asset = JSON.parse(readFileSync(new URL('../../main/resources/static/images/survive/adventurer.json', import.meta.url), 'utf8'));
  const actorArt = { adventurer: prepareActorArt(asset, { adventurer: true }) };
  const draw = action => {
    const mesh = meshBuilder(actorArt);
    mesh.avatar(0, 0, 0, [0.24, 0.31, 0.42], 'COMBAT', 0, 'SPEAR', action);
    return mesh.vertices;
  };
  const idle = draw(null);
  const ready = draw({ kind: 'ready', progress: 0.5, weight: 0.75 });
  assert.equal(ready.length, idle.length);
  assert.notDeepEqual(ready, idle);
  assert.ok(ready.every(Number.isFinite));
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

test('campfire faces the camera with one textured quad', () => {
  for (const yaw of [0, Math.PI / 2]) {
    const fire = meshBuilder();
    drawCampFlame(fire, yaw);
    assert.equal(fire.vertices.length, 6 * MESH_VERTEX_FLOATS);
    for (let offset = 0; offset < fire.vertices.length; offset += MESH_VERTEX_FLOATS) {
      const [x, y, z, , , , material, u, v] = fire.vertices.slice(offset, offset + MESH_VERTEX_FLOATS);
      assert.ok(Math.abs(Math.sin(yaw) * (x - 4) + Math.cos(yaw) * z) < 1e-12);
      assert.equal(y, v === 0 ? 0.27 : 2.40);
      assert.equal(material, 10.5);
      assert.ok(u === 0 || u === 1);
    }
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
