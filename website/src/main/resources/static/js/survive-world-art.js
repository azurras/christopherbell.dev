/** Display-only terrain and meshes. The server still owns island coordinates and action ranges. */
import { LANDMARKS } from './survive-world-model.js';

const TAU = Math.PI * 2;
export const MESH_VERTEX_FLOATS = 9;
const STONE_MATERIAL = 2.5;
const BARK_MATERIAL = 3.5;
const FOLIAGE_MATERIAL = 4.5;
const GRASS = [0.30, 0.37, 0.19];
const EARTH = [0.48, 0.41, 0.28];
const SAND = [0.63, 0.57, 0.40];
const STONE = [0.40, 0.42, 0.38];
const BARK = [0.27, 0.21, 0.15];
const mix = (a, b, amount) => a.map((value, index) => value + (b[index] - value) * amount);
const tint = (color, brightness) => color.map(channel => channel * brightness);
const clampUnit = value => Math.max(0, Math.min(1, value));

/** The same ground height anchors terrain, avatars, labels and the follow camera. */
export function groundHeight(x, z) {
  const radius = Math.hypot(x, z);
  const inland = clampUnit((78 - radius) / 20);
  const outsideCamp = clampUnit((radius - 5) / 17);
  const rollingGround = 2.1 + 1.4 * Math.sin(x * 0.097 + z * 0.075) + 1.15 * Math.cos(z * 0.142 - x * 0.051);
  const ridge = 3.4 * Math.exp(-((x - 34) ** 2 + (z + 19) ** 2) / 220);
  return (rollingGround + ridge) * inland * outsideCamp - (1 - inland) * 0.52;
}

function trailDistance(x, z) {
  let distance = Math.hypot(x, z) * 0.21;
  for (const landmark of LANDMARKS.slice(1)) {
    const length = Math.hypot(landmark.x, landmark.z);
    const along = (x * landmark.x + z * landmark.z) / length;
    if (along < 0 || along > length + 3) continue;
    const across = (x * landmark.z - z * landmark.x) / length;
    distance = Math.min(distance, Math.abs(across - 1.15 * Math.sin(along * 0.17)));
  }
  return distance;
}

function groundSurface(x, z) {
  const distance = Math.hypot(x, z);
  const vegetation = 0.48 + 0.25 * Math.sin(x * 0.12 + z * 0.04) + 0.18 * Math.cos(z * 0.21);
  const grass = mix(GRASS, [0.39, 0.43, 0.22], vegetation);
  const trail = clampUnit((3.1 - trailDistance(x, z)) / 2.4);
  const shore = clampUnit((distance - 63 - Math.sin(x * 0.18) * 2) / 11);
  return { color: mix(mix(grass, EARTH, trail), SAND, shore), material: 1 + Math.max(trail, shore) * 0.9 };
}

/** Build the static landscape once. All variation is deterministic across clients. */
export function islandMesh() {
  const mesh = meshBuilder();
  mesh.ellipsoid(0, 0, 0, 174, 135, 174, [1, 1, 1], 28, 10);
  mesh.disk(0, -0.66, 0, 380, [0.19, 0.34, 0.39], 72);
  const sectors = 144;
  const rings = 52;
  let innerRing = Array.from({ length: sectors }, () => [0, 0, 0]);
  for (let ring = 1; ring <= rings; ring++) {
    const outerRing = Array.from({ length: sectors }, (_, sector) => {
      const angle = sector / sectors * TAU;
      const radius = ring * 1.5;
      const x = Math.cos(angle) * radius;
      const z = Math.sin(angle) * radius;
      return [x, groundHeight(x, z), z];
    });
    for (let sector = 0; sector < sectors; sector++) {
      const next = (sector + 1) % sectors;
      const points = [innerRing[sector], outerRing[sector], outerRing[next], innerRing[next]];
      const materials = points.map(([x, , z]) => groundSurface(x, z));
      const paint = indices => mesh.triangle(...indices.map(index => points[index]),
        indices.map(index => materials[index].color), indices.map(index => materials[index].material));
      paint([0, 1, 2]);
      if (ring > 1) paint([0, 2, 3]);
    }
    innerRing = outerRing;
  }
  for (let index = 0; index < 142; index++) {
    const angle = index * 2.39996;
    const radius = 18 + (index * 19 % 51);
    const x = Math.cos(angle) * radius;
    const z = Math.sin(angle) * radius;
    if (trailDistance(x, z) < 4 || Math.hypot(x - 31, z + 14) < 12 || Math.hypot(x + 36, z - 25) < 9) continue;
    mesh.tree(x, z, 5.1 + (index * 13 % 28) * 0.19, angle, index % 4 === 0);
  }
  for (let index = 0; index < 28; index++) {
    const angle = index * 2.39996;
    const radius = 4 + Math.sqrt(index) * 2;
    const x = -24 + Math.cos(angle) * radius;
    const z = -15 + Math.sin(angle) * radius;
    if (trailDistance(x, z) > 3) mesh.tree(x, z, 6.4 + (index % 7) * 0.54, angle, index % 5 === 0);
  }
  for (let index = 0; index < 37; index++) {
    const angle = index * 2.39996;
    const radius = 2 + Math.sqrt(index) * 1.6;
    const x = 31 + Math.cos(angle) * radius;
    const z = -15 + Math.sin(angle) * radius;
    if (trailDistance(x, z) > 2.5) {
      const size = 0.9 + (index * 7 % 13) * 0.21;
      mesh.ellipsoid(x, groundHeight(x, z) + size * 0.21, z, size, size * 0.62, size * 0.8,
        tint(STONE, 0.81 + index % 6 * 0.055), 7, 4, angle, STONE_MATERIAL);
    }
  }
  for (let index = 0; index < 300; index++) {
    const angle = index * 2.39996;
    const radius = 4 + Math.sqrt(index) * 3.6;
    const x = Math.cos(angle) * radius;
    const z = Math.sin(angle) * radius;
    if (trailDistance(x, z) > 2.2) mesh.grass(x, z, angle, 0.16 + index % 5 * 0.055);
  }
  for (const landmark of LANDMARKS.slice(1)) {
    const x = landmark.x + 2.7;
    const z = landmark.z + 1.6;
    const y = groundHeight(x, z);
    mesh.beam([x, y, z], [x, y + 1.8, z], 0.14, 0.11, BARK, 5);
    mesh.box(x, y + 1.64, z, 1.05, 0.37, 0.14, [0.57, 0.44, 0.26]);
  }
  for (let index = 0; index < 42; index++) {
    const angle = index * 2.39996;
    const radius = 6 + Math.sqrt(index) * 9;
    const x = Math.cos(angle) * radius;
    const z = Math.sin(angle) * radius;
    const size = 0.14 + (index % 5) * 0.09;
    mesh.ellipsoid(x, groundHeight(x, z) + size * 0.25, z, size, size * 0.54, size * 0.82,
      tint(STONE, 0.82 + index % 4 * 0.07), 6, 3, angle);
  }
  for (let index = 0; index < 9; index++) {
    const angle = index / 9 * TAU;
    mesh.ellipsoid(4 + Math.cos(angle) * 1.1, 0.14, Math.sin(angle) * 1.1, 0.33, 0.25, 0.27, STONE, 6, 3);
  }
  mesh.beam([3.3, 0.25, -0.45], [4.7, 0.24, 0.45], 0.16, 0.13, BARK, 5);
  mesh.beam([3.3, 0.17, 0.43], [4.7, 0.22, -0.43], 0.16, 0.13, BARK, 5);
  mesh.cone(4, 0.24, 0, 0.51, 1.16, [0.99, 0.52, 0.19], 7);
  mesh.cone(4, 0.27, 0, 0.32, 0.78, [1, 0.79, 0.29], 6);
  for (let plank = 0; plank < 19; plank++) {
    mesh.box(0, 0.72, 68 + plank * 0.63, 3.35, 0.18, 0.59, tint([0.44, 0.32, 0.21], 0.88 + plank % 4 * 0.08));
  }
  // The bank has a short weathered deck, with pilings visible where the land falls away.
  for (let plank = 0; plank < 12; plank++) {
    const z = 71 + plank * 0.54;
    mesh.box(-13, 0.42, z, 2.45, 0.15, 0.49, tint(BARK, 1.22 + plank % 3 * 0.12));
  }
  for (const side of [-1, 1]) {
    for (const z of [71, 74, 77]) {
      const x = -13 + side * 1.05;
      mesh.beam([x, -0.65, z], [x, 0.71, z], 0.14, 0.12, BARK, 6);
    }
  }
  const workbenchY = groundHeight(-5, 3);
  mesh.box(-5, workbenchY + 0.98, 3, 2, 0.22, 1.05, [0.43, 0.33, 0.24]);
  for (const side of [-1, 1]) {
    mesh.beam([-5 + side * 0.84, workbenchY, 2.66], [-5 + side * 0.77, workbenchY + 0.9, 2.77], 0.14, 0.11, BARK, 5);
    mesh.beam([-5 + side * 0.84, workbenchY, 3.34], [-5 + side * 0.77, workbenchY + 0.9, 3.23], 0.14, 0.11, BARK, 5);
  }
  mesh.box(-5.32, workbenchY + 1.14, 3.11, 0.56, 0.09, 0.29, STONE);
  buildRuinedGate(mesh);
  return mesh;
}

/** Weathered masonry landmark north of camp. The central arch leaves the approach open. */
function buildRuinedGate(mesh) {
  const z = -18;
  const foundation = groundHeight(0, z) - 0.35;
  const masonry = [0.53, 0.53, 0.43];
  for (const side of [-1, 1]) {
    const x = side * 3.18;
    mesh.box(x, foundation + 0.35, z, 2.1, 0.70, 3.1, tint(masonry, 0.85), STONE_MATERIAL);
    mesh.box(x, foundation + 2.12, z, 1.55, 3.56, 2.05, masonry, STONE_MATERIAL);
    mesh.box(x, foundation + 3.83, z, 1.9, 0.43, 2.35, tint(masonry, 1.13), STONE_MATERIAL);
    for (let section = 0; section < 5; section++) {
      const wallX = side * (4.5 + section * 1.45);
      const wallY = groundHeight(wallX, z) - 0.25;
      const height = [2.7, 2.35, 1.95, 1.65, 1.1][section];
      mesh.box(wallX, wallY + height / 2, z, 1.51, height, 1.38, tint(masonry, 0.83 + section * 0.025), STONE_MATERIAL);
      mesh.box(wallX, wallY + height + 0.10, z, 1.55, 0.20, 1.65, [0.37, 0.43, 0.25], STONE_MATERIAL);
    }
  }
  const center = foundation + 3.55;
  const innerRadius = 2.37;
  const outerRadius = 3.30;
  for (let index = 0; index < 11; index++) {
    const from = index * Math.PI / 11 + 0.013;
    const to = (index + 1) * Math.PI / 11 - 0.013;
    const arc = (angle, radius, face) => [Math.cos(angle) * radius, center + Math.sin(angle) * radius, z + face];
    const faces = [-0.96, 0.96].map(face => [arc(from, innerRadius, face), arc(from, outerRadius, face),
      arc(to, outerRadius, face), arc(to, innerRadius, face)]);
    for (const [a, b, c, d] of faces) {
      const color = tint(masonry, index === 5 ? 1.15 : 0.9 + index % 4 * 0.04);
      mesh.triangle(a, b, c, color, STONE_MATERIAL);
      mesh.triangle(a, c, d, color, STONE_MATERIAL);
    }
    for (let edge = 0; edge < 4; edge++) {
      const next = (edge + 1) % 4;
      mesh.triangle(faces[0][edge], faces[1][edge], faces[1][next], masonry, STONE_MATERIAL);
      mesh.triangle(faces[0][edge], faces[1][next], faces[0][next], masonry, STONE_MATERIAL);
    }
  }
  for (let paving = 0; paving < 10; paving++) {
    const pavingZ = z + 4.1 - paving * 0.84;
    const pavingY = groundHeight(0, pavingZ);
    for (const side of [-1, 1]) {
      mesh.box(side * 1.04, pavingY + 0.025, pavingZ, 1.99, 0.14, 0.77,
        tint(masonry, 0.91 + paving % 3 * 0.05), STONE_MATERIAL);
    }
  }
}

function cross(a, b) {
  return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
}

/** Store interleaved world-space positions and baked directional color for WebGL. */
export function meshBuilder() {
  const vertices = [];
  function triangle(a, b, c, color, material = 0, coordinates = null) {
    const normal = cross(b.map((value, index) => value - a[index]), c.map((value, index) => value - a[index]));
    const length = Math.hypot(...normal) || 1;
    const shade = 0.66 + 0.34 * Math.abs((-normal[0] * 0.39 + normal[1] * 0.83 - normal[2] * 0.36) / length);
    const colors = Array.isArray(color[0]) ? color : [color, color, color];
    const weights = Array.isArray(material) ? material : [material, material, material];
    const [nx, ny, nz] = normal.map(Math.abs);
    [a, b, c].forEach((point, index) => {
      const [x, y, z] = point;
      const uv = coordinates?.[index] || (weights[index] < 2 || (ny >= nx && ny >= nz) ? [x, z] : nx > nz ? [z, y] : [x, y]);
      vertices.push(...point, ...tint(colors[index], shade), weights[index], ...uv);
    });
  }
  function box(x, y, z, width, height, depth, color, material = 0) {
    const points = [];
    for (const dy of [-1, 1]) for (const dz of [-1, 1]) for (const dx of [-1, 1]) {
      points.push([x + dx * width / 2, y + dy * height / 2, z + dz * depth / 2]);
    }
    for (const [a, b, c, d] of [[0, 1, 3, 2], [4, 6, 7, 5], [0, 4, 5, 1], [2, 3, 7, 6], [0, 2, 6, 4], [1, 5, 7, 3]]) {
      triangle(points[a], points[b], points[c], color, material);
      triangle(points[a], points[c], points[d], color, material);
    }
  }
  function beam(start, end, baseRadius, tipRadius, color, sides = 6, material = 0) {
    const axis = end.map((value, index) => value - start[index]);
    const length = Math.hypot(...axis);
    if (!length) return;
    const normal = axis.map(value => value / length);
    const side = cross(normal, Math.abs(normal[1]) > 0.9 ? [1, 0, 0] : [0, 1, 0]);
    const sideLength = Math.hypot(...side);
    const tangent = side.map(value => value / sideLength);
    const bitangent = cross(normal, tangent);
    const point = (center, radius, angle) => center.map((value, index) =>
      value + radius * (Math.cos(angle) * tangent[index] + Math.sin(angle) * bitangent[index]));
    for (let index = 0; index < sides; index++) {
      const angle = index / sides * TAU;
      const next = (index + 1) / sides * TAU;
      const a = point(start, baseRadius, angle);
      const b = point(start, baseRadius, next);
      const c = point(end, tipRadius, next);
      const d = point(end, tipRadius, angle);
      triangle(a, b, c, color, material);
      if (tipRadius > 0) triangle(a, c, d, color, material);
    }
  }
  function cone(x, y, z, radius, height, color, sides = 8) {
    if (height) beam([x, y, z], [x, y + height, z], radius, 0, color, sides);
    else disk(x, y, z, radius, color, sides);
  }
  function disk(x, y, z, radius, color, sides = 12) {
    for (let index = 0; index < sides; index++) {
      const angle = index / sides * TAU;
      const next = (index + 1) / sides * TAU;
      triangle([x, y, z], [x + Math.cos(angle) * radius, y, z + Math.sin(angle) * radius],
        [x + Math.cos(next) * radius, y, z + Math.sin(next) * radius], color);
    }
  }
  function ellipsoid(x, y, z, width, height, depth, color, sides = 8, rows = 4, heading = 0, material = 0) {
    const sin = Math.sin(heading);
    const cos = Math.cos(heading);
    const point = (row, column) => {
      const latitude = row / rows * Math.PI;
      const longitude = column / sides * TAU;
      const across = Math.sin(latitude) * Math.cos(longitude) * width;
      const forward = Math.sin(latitude) * Math.sin(longitude) * depth;
      return [x + across * cos + forward * sin, y + Math.cos(latitude) * height, z - across * sin + forward * cos];
    };
    for (let row = 0; row < rows; row++) for (let column = 0; column < sides; column++) {
      const a = point(row, column);
      const b = point(row + 1, column);
      const c = point(row + 1, column + 1);
      const d = point(row, column + 1);
      if (row < rows - 1) triangle(a, b, c, color, material);
      if (row > 0) triangle(a, c, d, color, material);
    }
  }
  function tree(x, z, height, angle, broadleaf) {
    const y = groundHeight(x, z);
    const leanX = Math.cos(angle) * height * 0.10;
    const leanZ = Math.sin(angle) * height * 0.10;
    beam([x, y, z], [x + leanX, y + height * 0.92, z + leanZ], height * 0.058, 0.045, BARK, 9, BARK_MATERIAL);
    for (let branch = 0; branch < 6; branch++) {
      const branchAngle = angle + branch * 2.39996;
      const level = 0.40 + branch * 0.068;
      const spread = height * (0.26 - branch * 0.026);
      const trunk = [x + leanX * level, y + height * level, z + leanZ * level];
      const tip = [trunk[0] + Math.cos(branchAngle) * spread, trunk[1] + height * 0.055, trunk[2] + Math.sin(branchAngle) * spread];
      beam(trunk, tip, 0.11, 0.025, BARK, 5, BARK_MATERIAL);
      const foliage = tint(broadleaf ? [0.33, 0.42, 0.23] : [0.22, 0.34, 0.23], 0.83 + branch * 0.071);
      if (broadleaf) {
        ellipsoid(tip[0], tip[1] + height * 0.11, tip[2], height * 0.14, height * 0.11, height * 0.13, foliage, 7, 3, branchAngle);
      } else {
        const stem = [trunk[0] + (tip[0] - trunk[0]) * 0.44, tip[1], trunk[2] + (tip[2] - trunk[2]) * 0.44];
        beam(stem, [tip[0], tip[1] + height * 0.16, tip[2]], height * (0.15 - branch * 0.009), 0, foliage, 7);
        beam(stem, [trunk[0], tip[1] + height * 0.22, trunk[2]], height * 0.14, 0, tint(foliage, 1.09), 7);
      }
      foliageCard(tip, height * (broadleaf ? 0.52 : 0.30), branchAngle, tint(foliage, 2.6));
      foliageCard(tip, height * (broadleaf ? 0.45 : 0.26), branchAngle + Math.PI / 2, tint(foliage, 2.85));
    }
    ellipsoid(x + leanX, y + height * 0.89, z + leanZ, height * 0.14, height * 0.20, height * 0.14,
      broadleaf ? [0.35, 0.43, 0.24] : [0.28, 0.39, 0.24], 7, 3, angle);
  }
  function foliageCard([x, y, z], size, angle, color) {
    const dx = Math.cos(angle) * size * 0.5;
    const dz = Math.sin(angle) * size * 0.5;
    const a = [x - dx, y - size * 0.15, z - dz];
    const b = [x + dx, y - size * 0.15, z + dz];
    const c = [x + dx * 0.78, y + size * 0.65, z + dz * 0.78];
    const d = [x - dx * 0.78, y + size * 0.65, z - dz * 0.78];
    triangle(a, b, c, color, FOLIAGE_MATERIAL, [[0, 1], [1, 1], [1, 0]]);
    triangle(a, c, d, color, FOLIAGE_MATERIAL, [[0, 1], [1, 0], [0, 0]]);
  }
  function grass(x, z, angle, height) {
    const y = groundHeight(x, z);
    for (let blade = 0; blade < 4; blade++) {
      const heading = angle + blade * 1.8;
      const dx = Math.cos(heading) * height;
      const dz = Math.sin(heading) * height;
      triangle([x - dz * 0.18, y, z + dx * 0.18], [x + dz * 0.18, y, z - dx * 0.18],
        [x + dx * 0.78, y + height * (1 - blade * 0.12), z + dz * 0.78], tint([0.36, 0.42, 0.21], 0.82 + blade * 0.12));
    }
  }
  function avatar(x, z, heading, cloth, status, stride = 0, weapon, action = null) {
    const y = groundHeight(x, z);
    const resting = status === 'SLEEPING' || status === 'DEAD' || (status === 'ESCAPED' && action?.kind !== 'row');
    const cos = Math.cos(heading);
    const sin = Math.sin(heading);
    const crouch = action?.kind === 'rest' ? action.weight * 0.38 : 0;
    const pose = (across, height, forward = 0) => resting
      ? [x + across * cos + height * sin, y + 0.31 + forward, z - across * sin + height * cos]
      : [x + across * cos + forward * sin, y + height - crouch * Math.min(1, height), z - across * sin + forward * cos];
    const leather = [0.34, 0.21, 0.16];
    const trousers = [0.23, 0.25, 0.27];
    const skin = [0.72, 0.55, 0.42];
    const hardware = [0.58, 0.55, 0.43];
    const part = (at, width, height, depth, color) =>
      ellipsoid(...at, width, resting ? depth : height, resting ? height : depth, color, 7, 3, heading);
    const shadowHeight = y + 0.045;
    disk(x, shadowHeight, z, 0.68, [0.18, 0.21, 0.13], 10);
    const hip = pose(0, 1.1);
    const shoulders = pose(0, 1.88, action?.kind === 'sleep' ? action.weight * 0.045 : 0);
    beam(hip, shoulders, 0.32, 0.47, cloth, 7);
    beam(pose(0, 0.99), pose(0, 1.27), 0.37, 0.31, [0.43, 0.29, 0.20], 7);
    beam(pose(-0.30, 1.89, -0.33), pose(0.30, 1.27, -0.29), 0.045, 0.043, leather, 5);
    beam(pose(-0.30, 1.89, 0.34), pose(0.30, 1.27, 0.28), 0.045, 0.043, leather, 5);
    part(pose(0, 1.29), 0.38, 0.11, 0.28, leather);
    part(pose(0, 1.29, 0.28), 0.095, 0.07, 0.05, hardware);
    part(pose(0, 1.66, -0.27), 0.27, 0.32, 0.16, leather);
    part(pose(0, 2.12), 0.13, 0.23, 0.13, skin);
    part(pose(0, 2.04), 0.25, 0.10, 0.20, [0.26, 0.27, 0.24]);
    part(pose(0, 2.32, 0.02), 0.23, 0.29, 0.22, skin);
    part(pose(0, 2.46, -0.06), 0.245, 0.20, 0.23, [0.21, 0.16, 0.12]);
    part(pose(0, 2.29, 0.225), 0.071, 0.065, 0.05, skin);
    let toolHand;
    for (const side of [-1, 1]) {
      const swing = resting ? 0 : stride * side;
      const knee = pose(side * 0.19, 0.61 + Math.max(0, swing) * 0.19, swing * 0.34);
      const ankle = pose(side * 0.20, 0.16 + Math.max(0, swing) * 0.16, swing * 0.58);
      beam(pose(side * 0.19, 1.15), knee, 0.18, 0.14, trousers);
      beam(knee, ankle, 0.15, 0.12, leather);
      part(pose(side * 0.19, 0.54 + Math.max(0, swing) * 0.17, swing * 0.36), 0.17, 0.07, 0.145, [0.51, 0.37, 0.25]);
      part(pose(side * 0.20, 0.13 + Math.max(0, swing) * 0.16, swing * 0.58 + 0.10), 0.15, 0.13, 0.23, leather);
      const reach = resting ? null : actionReach(action, side);
      const elbow = pose(side * 0.51, 1.48 + (reach?.lift ?? 0) * 0.48,
        -swing * 0.29 + (reach?.forward ?? 0) * 0.45);
      const wrist = pose(side * 0.55 - (reach?.inward ?? 0) * side, 1.14 + (reach?.lift ?? 0),
        0.09 - swing * 0.42 + (reach?.forward ?? 0));
      part(pose(side * 0.41, 1.85), 0.23, 0.16, 0.22, [0.49, 0.49, 0.43]);
      beam(pose(side * 0.44, 1.84), elbow, 0.16, 0.12, cloth);
      beam(elbow, wrist, 0.12, 0.09, leather);
      part(wrist, 0.105, 0.135, 0.10, skin);
      if (side === 1) toolHand = wrist;
    }
    const tool = resting ? null : action?.kind;
    if (['chop', 'mine', 'hammer', 'fish', 'strike', 'row'].includes(tool)) {
      const length = tool === 'fish' ? 2.1 : tool === 'row' || (tool === 'strike' && weapon === 'SPEAR') ? 1.5 : 0.82;
      const arc = Math.sin(action.progress * Math.PI * (tool === 'fish' ? 2 : 4));
      const tip = [toolHand[0] + sin * length * 0.73, toolHand[1] + length * (0.38 + 0.55 * arc),
        toolHand[2] + cos * length * 0.73];
      beam(toolHand, tip, 0.043, 0.023, [0.49, 0.34, 0.20], 5);
      if (tool === 'fish') {
        const float = [x + sin * 13, -0.59, z + cos * 13];
        beam(tip, float, 0.016, 0.013, [0.70, 0.68, 0.51], 3);
        ellipsoid(...float, 0.095, 0.13, 0.095, [0.77, 0.27, 0.16], 6, 3);
      } else {
        const blade = tool === 'mine' ? 0.31 : 0.20;
        ellipsoid(...tip, blade, tool === 'chop' ? 0.18 : 0.10, 0.085, hardware, 6, 3, heading);
      }
    } else if (tool === 'eat' || tool === 'give') {
      ellipsoid(...toolHand, 0.13, 0.09, 0.13, [0.65, 0.45, 0.23], 6, 3);
    }
    if (weapon && tool !== 'strike') {
      const length = weapon === 'SPEAR' ? 2.8 : 1.65;
      const haft = pose(0.32, 0.58, -0.36);
      const tip = pose(-0.35, 0.58 + length, -0.37);
      beam(haft, tip, 0.055, 0.044, [0.43, 0.30, 0.17], 5);
      if (weapon !== 'CLUB') part(tip, 0.14, 0.20, 0.07, hardware);
    }
  }
  function animal(x, z, kind, scale = 1, heading = 0, stride = 0, recoil = 0) {
    const y = groundHeight(x, z);
    const wolf = kind === 'WOLF';
    const bear = kind === 'BEAR';
    const fur = wolf ? [0.43, 0.45, 0.44] : bear ? [0.36, 0.25, 0.18] : [0.42, 0.31, 0.25];
    const sin = Math.sin(heading);
    const cos = Math.cos(heading);
    const p = (dx, dy, dz) => [x + ((dx - recoil * 0.24) * sin + dz * cos) * scale,
      y + dy * scale, z + ((dx - recoil * 0.24) * cos - dz * sin) * scale];
    const body = (dx, dy, dz, w, h, d, color) =>
      ellipsoid(...p(dx, dy, dz), w * scale, h * scale, d * scale, color, 8, 4, heading - Math.PI / 2);
    body(0, 0.85, 0, 0.99, bear ? 0.73 : 0.48, 0.48, fur);
    body(0.75, bear ? 0.98 : 0.81, 0, 0.44, 0.39, 0.36, tint(fur, 1.08));
    body(wolf ? 1.12 : 1.04, 0.71, 0, 0.35, 0.20, 0.22, tint(fur, 1.21));
    for (const side of [-1, 1]) {
      for (const offset of [-0.62, 0.55]) {
        const swing = stride * side * Math.sign(offset);
        beam(p(offset, 0.76, side * 0.29), p(offset - 0.09 + swing * 0.26, 0.09 + Math.max(0, swing) * 0.13, side * 0.31),
          (bear ? 0.20 : 0.13) * scale, 0.085 * scale, fur);
      }
      if (wolf) beam(p(0.65, 0.96, side * 0.22), p(0.52, 1.37, side * 0.28), 0.17 * scale, 0, fur, 5);
      else body(0.61, bear ? 1.24 : 1.09, side * 0.24, 0.13, 0.18, 0.12, fur);
      body(0.90, 0.88, side * 0.276, 0.047, 0.039, 0.026, [0.17, 0.14, 0.12]);
    }
    if (wolf) beam(p(-0.82, 0.89, 0), p(-1.45, 0.48, 0), 0.17 * scale, 0.04 * scale, fur);
  }
  return { vertices, triangle, box, beam, cone, disk, ellipsoid, tree, grass, avatar, animal };
}

function actionReach(action, side) {
  if (!action) return null;
  const { kind, progress, weight } = action;
  const swing = Math.sin(progress * Math.PI * 4) * weight;
  if (['chop', 'mine', 'hammer', 'strike', 'row'].includes(kind)) {
    return { lift: 0.38 * weight + 0.75 * swing, forward: 0.49 * weight - 0.27 * swing, inward: 0.19 * weight };
  }
  if (kind === 'fish') return { lift: (0.42 + 0.28 * Math.sin(progress * Math.PI * 2)) * weight, forward: 0.66 * weight };
  if (kind === 'wake') return { lift: weight * 1.37, forward: weight * 0.19 };
  if (kind === 'eat' && side === 1) return { lift: weight * 1.03, forward: weight * 0.23, inward: weight * 0.38 };
  if (kind === 'aim' || kind === 'brace' || kind === 'give') {
    return { lift: weight * 0.47, forward: weight * 0.44, inward: weight * 0.19 };
  }
  if (kind === 'lower') return { lift: weight * 0.22, forward: weight * 0.12 };
  return null;
}
