/** Display-only terrain and meshes. The server still owns island coordinates and action ranges. */
import { LANDMARKS } from './survive-world-model.js';
import { createActorModels } from './survive-world-actors.js';
import { addResourceProps, drawQuarryRock } from './survive-world-props.js';

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

function groundSurface(x, z, trees) {
  const distance = Math.hypot(x, z);
  const vegetation = 0.48 + 0.25 * Math.sin(x * 0.12 + z * 0.04) + 0.18 * Math.cos(z * 0.21);
  const grass = mix(GRASS, [0.39, 0.43, 0.22], vegetation);
  const trail = clampUnit((3.1 - trailDistance(x, z)) / 2.4);
  const shore = clampUnit((distance - 63 - Math.sin(x * 0.18) * 2) / 11);
  let shade = 0;
  for (const tree of trees) {
    const dx = x - tree.x;
    const dz = z - tree.z;
    if (Math.abs(dx) > 6 || Math.abs(dz) > 7) continue;
    const distanceFromCanopy = Math.hypot(dx - tree.height * 0.20, dz + tree.height * 0.25);
    shade = Math.max(shade, clampUnit((tree.height * 0.38 - distanceFromCanopy) / 1.7));
  }
  return { color: tint(mix(mix(grass, EARTH, trail), SAND, shore), 1 - shade * 0.46), material: 1 + Math.max(trail, shore) * 0.9 };
}

function woodland() {
  const trees = [];
  for (let index = 0; index < 142; index++) {
    const angle = index * 2.39996;
    const radius = 18 + (index * 19 % 51);
    const x = Math.cos(angle) * radius;
    const z = Math.sin(angle) * radius;
    if (trailDistance(x, z) < 4 || Math.hypot(x - 31, z + 14) < 12 || Math.hypot(x + 36, z - 25) < 9) continue;
    trees.push({ x, z, height: 5.1 + (index * 13 % 28) * 0.19, angle, broadleaf: index % 4 === 0 });
  }
  for (let index = 0; index < 28; index++) {
    const angle = index * 2.39996;
    const radius = 4 + Math.sqrt(index) * 2;
    const x = -24 + Math.cos(angle) * radius;
    const z = -15 + Math.sin(angle) * radius;
    if (trailDistance(x, z) > 3) trees.push({ x, z, height: 6.4 + (index % 7) * 0.54, angle, broadleaf: index % 5 === 0 });
  }
  return trees;
}

/** Build the static landscape once. All variation is deterministic across clients. */
export function islandMesh() {
  const mesh = meshBuilder();
  const trees = woodland();
  mesh.ellipsoid(0, 0, 0, 174, 135, 174, [1, 1, 1], 28, 10);
  mesh.disk(0, -0.66, 0, 380, [0.19, 0.34, 0.39], 72);
  const sectors = 144;
  const rings = 52;
  const terrainVertex = (x, z) => ({ point: [x, groundHeight(x, z), z], ...groundSurface(x, z, trees) });
  let innerRing = Array.from({ length: sectors }, () => terrainVertex(0, 0));
  for (let ring = 1; ring <= rings; ring++) {
    const outerRing = Array.from({ length: sectors }, (_, sector) => {
      const angle = sector / sectors * TAU;
      const radius = ring * 1.5;
      return terrainVertex(Math.cos(angle) * radius, Math.sin(angle) * radius);
    });
    for (let sector = 0; sector < sectors; sector++) {
      const next = (sector + 1) % sectors;
      const face = [innerRing[sector], outerRing[sector], outerRing[next], innerRing[next]];
      const paint = indices => mesh.triangle(...indices.map(index => face[index].point),
        indices.map(index => face[index].color), indices.map(index => face[index].material));
      paint([0, 1, 2]);
      if (ring > 1) paint([0, 2, 3]);
    }
    innerRing = outerRing;
  }
  for (const tree of trees) mesh.tree(tree.x, tree.z, tree.height, tree.angle, tree.broadleaf);
  for (let index = 0; index < 37; index++) {
    const angle = index * 2.39996;
    const radius = 2 + Math.sqrt(index) * 1.6;
    const x = 31 + Math.cos(angle) * radius;
    const z = -15 + Math.sin(angle) * radius;
    if (trailDistance(x, z) > 2.5) {
      const size = 0.9 + (index * 7 % 13) * 0.21;
      drawQuarryRock(mesh, x, groundHeight(x, z), z, size, angle, index % 4 === 0);
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
  for (let plank = 0; plank < 19; plank++) {
    mesh.box(0, 0.72, 68 + plank * 0.63, 3.35, 0.18, 0.59, tint([0.44, 0.32, 0.21], 0.88 + plank % 4 * 0.08), BARK_MATERIAL);
  }
  // The bank has a short weathered deck, with pilings visible where the land falls away.
  for (let plank = 0; plank < 12; plank++) {
    const z = 71 + plank * 0.54;
    mesh.box(-13, 0.42, z, 2.45, 0.15, 0.49, tint(BARK, 1.22 + plank % 3 * 0.12), BARK_MATERIAL);
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
  addResourceProps(mesh, groundHeight);
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
export function meshBuilder(actorArt) {
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
      const u = index / sides;
      const v = (index + 1) / sides;
      triangle(a, b, c, color, material, material > 5 ? [[u, 1], [v, 1], [v, 0]] : null);
      if (tipRadius > 0) triangle(a, c, d, color, material, material > 5 ? [[u, 1], [v, 0], [u, 0]] : null);
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
      const uv = material > 5 ? [[column / sides, row / rows], [column / sides, (row + 1) / rows],
        [(column + 1) / sides, (row + 1) / rows], [(column + 1) / sides, row / rows]] : null;
      if (row < rows - 1) triangle(a, b, c, color, material, uv?.slice(0, 3));
      if (row > 0) triangle(a, c, d, color, material, uv && [uv[0], uv[2], uv[3]]);
    }
  }
  function tree(x, z, height, angle, broadleaf) {
    const y = groundHeight(x, z);
    const leanX = Math.cos(angle) * height * 0.055;
    const leanZ = Math.sin(angle) * height * 0.055;
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
  const actors = createActorModels({ triangle, beam, ellipsoid, groundHeight, actorArt });
  return { vertices, triangle, box, beam, cone, disk, ellipsoid, tree, grass, ...actors };
}
