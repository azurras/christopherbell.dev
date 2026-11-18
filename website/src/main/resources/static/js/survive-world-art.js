/** Display-only terrain and meshes. The server still owns island coordinates and action ranges. */
import { LANDMARKS } from './survive-world-model.js';
import { createActorModels } from './survive-world-actors.js';
import { addFishingBank, addResourceProps, drawQuarryRock } from './survive-world-props.js';

const TAU = Math.PI * 2;
export const MESH_VERTEX_FLOATS = 9;
const STONE_MATERIAL = 2.5;
const BARK_MATERIAL = 3.5;
const TRUNK_MATERIAL = 3.75;
const FOLIAGE_MATERIAL = 4.5;
const STREAM_MATERIAL = 13.5;
const LANTERN_MATERIAL = 14.5;
const BRIDGE_HEIGHT = 0.43;
const WAYSTATION_X = 3.5;
const WAYSTATION_Z = -38;
const GRASS = [0.30, 0.37, 0.19];
const EARTH = [0.48, 0.41, 0.28];
const SAND = [0.63, 0.57, 0.40];
const STONE = [0.40, 0.42, 0.38];
const BARK = [0.27, 0.21, 0.15];
const mix = (a, b, amount) => a.map((value, index) => value + (b[index] - value) * amount);
const tint = (color, brightness) => color.map(channel => channel * brightness);
const clampUnit = value => Math.max(0, Math.min(1, value));

function streamCenter(x) {
  return -26 + Math.sin(x * 0.18) * 0.75;
}

function terrainHeight(x, z) {
  const radius = Math.hypot(x, z);
  const inland = clampUnit((78 - radius) / 20);
  const outsideCamp = clampUnit((radius - 5) / 17);
  const rollingGround = 2.1 + 1.4 * Math.sin(x * 0.097 + z * 0.075) + 1.15 * Math.cos(z * 0.142 - x * 0.051);
  const ridge = 3.4 * Math.exp(-((x - 34) ** 2 + (z + 19) ** 2) / 220);
  const base = (rollingGround + ridge) * inland * outsideCamp - (1 - inland) * 0.52;
  const banks = 3.8 * Math.exp(-((Math.abs(x) - 8) ** 2 / 17 + (z + 13) ** 2 / 35));
  const distanceFromStream = Math.abs(z - streamCenter(x));
  const streamReach = clampUnit((15 - Math.abs(x)) / 4);
  const channel = streamReach * clampUnit((2.7 - distanceFromStream) / 1.5);
  const bankReach = streamReach * clampUnit((4.1 - distanceFromStream) / 1.4);
  const uphillBank = base + banks;
  // Fill low spots beside the cutting so the water ends against earth.
  const bankHeight = uphillBank + Math.max(0, 0.08 - uphillBank) * bankReach;
  return bankHeight + (-0.60 - bankHeight) * channel;
}

/** Keep the point light centered inside the glass mounted beside the waystation door. */
export const WAYSTATION_LANTERN = Object.freeze([
  WAYSTATION_X + 0.79, terrainHeight(WAYSTATION_X, WAYSTATION_Z) + 1.48, WAYSTATION_Z + 1.96,
]);

/** Anchors walking and rendering to the surface, including the bridge deck. */
export function groundHeight(x, z) {
  const ground = terrainHeight(x, z);
  if (Math.abs(x) >= 1.8 || Math.abs(z + 26) >= 5) return ground;
  const deck = clampUnit((1.8 - Math.abs(x)) / 0.25) * clampUnit((5 - Math.abs(z + 26)) / 1.75);
  return ground + (Math.max(ground, BRIDGE_HEIGHT) - ground) * deck;
}

function trailDistance(x, z) {
  let distance = Math.hypot(x, z) * 0.21;
  if (z < 0 && z > -41) distance = Math.min(distance, Math.abs(x));
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
  const terrainVertex = (x, z) => ({ point: [x, terrainHeight(x, z), z], ...groundSurface(x, z, trees) });
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
  // Continue the visible sand below water instead of ending it above the sea plane.
  const submergedShore = Array.from({ length: sectors }, (_, sector) => {
    const angle = sector / sectors * TAU;
    return [Math.cos(angle) * 80, -0.88, Math.sin(angle) * 80];
  });
  for (let sector = 0; sector < sectors; sector++) {
    const next = (sector + 1) % sectors;
    const dampSand = [0.37, 0.34, 0.25];
    mesh.triangle(innerRing[sector].point, submergedShore[sector], submergedShore[next], dampSand, 1.9);
    mesh.triangle(innerRing[sector].point, submergedShore[next], innerRing[next].point, dampSand, 1.9);
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
  for (let index = 0; index < 780; index++) {
    const angle = index * 2.39996;
    const radius = 4 + Math.sqrt(index) * 2.2;
    const x = Math.cos(angle) * radius;
    const z = Math.sin(angle) * radius;
    if (trailDistance(x, z) > 2.0) {
      mesh.grass(x, z, angle, 0.16 + index % 5 * 0.055);
      if (index % 9 === 0 && radius < 54) mesh.underbrush(x, z, angle);
    }
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
  mesh.disk(4, 0.026, 0, 0.93, [0.25, 0.21, 0.16], 18);
  mesh.disk(4, 0.032, 0, 0.58, [0.19, 0.16, 0.13], 14);
  for (let index = 0; index < 11; index++) {
    const angle = index / 11 * TAU;
    const radius = 0.91 + (index % 3) * 0.041;
    const x = 4 + Math.cos(angle) * radius;
    const z = Math.sin(angle) * radius;
    mesh.ellipsoid(x, groundHeight(x, z) + 0.09, z, 0.23 + (index % 4) * 0.031, 0.15 + (index % 3) * 0.04,
      0.23, tint(STONE, 0.78 + (index % 5) * 0.075), 7, 3, angle, STONE_MATERIAL);
  }
  mesh.beam([3.39, 0.25, -0.40], [4.61, 0.22, 0.40], 0.15, 0.12, BARK, 7, BARK_MATERIAL);
  mesh.beam([3.43, 0.16, 0.40], [4.57, 0.20, -0.40], 0.14, 0.11, BARK, 7, BARK_MATERIAL);
  for (let index = 0; index < 5; index++) {
    const angle = index * 2.39996;
    mesh.ellipsoid(4 + Math.cos(angle) * 0.39, 0.07, Math.sin(angle) * 0.39, 0.082, 0.052, 0.064,
      [0.31, 0.18, 0.12], 5, 3, angle);
  }
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
  addFishingBank(mesh, groundHeight);
  buildRuinedGate(mesh);
  buildGateCrossing(mesh);
  plantNorthGateVerge(mesh);
  return mesh;
}

/** Cluster plants along the road's edges, leaving the gate and bridge deck clear. */
function plantNorthGateVerge(mesh) {
  for (const side of [-1, 1]) {
    for (const section of [-6, -9, -13, -15, -20, -31, -34, -36, -40]) {
      const centerX = side * (3.4 + Math.sin(section * 1.7) * 0.7);
      for (let blade = 0; blade < 7; blade++) {
        const angle = blade * 2.39996 + section;
        const x = centerX + Math.cos(angle) * 0.75;
        const z = section + Math.sin(angle) * 0.65;
        mesh.grass(x, z, angle, 0.21 + (blade % 4) * 0.07);
      }
      if (section === -13 || section === -34 || section === -40) {
        mesh.underbrush(centerX + side * 1.1, section, section * 0.14);
      }
    }
  }
}

/** Give the water shader a cross-current coordinate and bed depth in meters. */
function buildGateStream(mesh) {
  const waterLevel = -0.33;
  const bankOffsets = [-2.7, -2.1, -1.65, -1.2, -0.6, 0, 0.6, 1.2, 1.65, 2.1, 2.7];
  function crossSection(x) {
    return bankOffsets.map(offset => {
      const z = streamCenter(x) + offset;
      const waterDepth = Math.max(0, waterLevel - terrainHeight(x, z));
      return { point: [x, waterLevel, z], coordinates: [offset, waterDepth] };
    });
  }
  let upstream = crossSection(-13);
  for (let x = -12; x <= 13; x++) {
    const downstream = crossSection(x);
    const paint = (a, b, c) => mesh.triangle(a.point, b.point, c.point,
      [0.19, 0.37, 0.36], STREAM_MATERIAL, [a.coordinates, b.coordinates, c.coordinates]);
    for (let bank = 0; bank < bankOffsets.length - 1; bank++) {
      paint(upstream[bank], downstream[bank], downstream[bank + 1]);
      paint(upstream[bank], downstream[bank + 1], upstream[bank + 1]);
    }
    upstream = downstream;
  }
}

/** The old gate leads to a timber crossing and a stone waystation. */
function buildGateCrossing(mesh) {
  buildGateStream(mesh);
  for (let board = 0; board < 15; board++) {
    const z = -30.55 + board * 0.65;
    mesh.box(0, groundHeight(0, z) - 0.08, z, 3.35, 0.15, 0.61,
      tint(BARK, 1.30 + board % 4 * 0.16), BARK_MATERIAL);
  }
  for (const side of [-1, 1]) {
    const x = side * 1.62;
    // Recess the lengthwise supports under the edges of the deck.
    mesh.box(side * 1.37, BRIDGE_HEIGHT - 0.22, -26, 0.18, 0.21, 7.3, tint(BARK, 0.84), BARK_MATERIAL);
    for (const z of [-28.7, -26, -23.3]) {
      mesh.beam([x, -0.54, z], [x, BRIDGE_HEIGHT + 0.75, z], 0.13, 0.12, BARK, 5, BARK_MATERIAL);
    }
    mesh.beam([x, BRIDGE_HEIGHT + 0.68, -29.1], [x, BRIDGE_HEIGHT + 0.68, -22.9],
      0.08, 0.08, BARK, 5, BARK_MATERIAL);
  }
  const waystationX = WAYSTATION_X;
  const waystationZ = WAYSTATION_Z;
  const base = terrainHeight(waystationX, waystationZ);
  const stone = [0.48, 0.48, 0.42];
  mesh.box(waystationX, base + 1.16, waystationZ, 4.1, 2.8, 3.4, stone, STONE_MATERIAL);
  const front = waystationZ + 1.72;
  mesh.box(waystationX, base + 0.74, front, 1.03, 1.84, 0.08, BARK, BARK_MATERIAL);
  mesh.box(waystationX, base + 1.71, front + 0.04, 1.48, 0.23, 0.28, tint(stone, 1.18), STONE_MATERIAL);
  mesh.box(waystationX, base - 0.05, front + 0.35, 1.49, 0.21, 0.64, stone, STONE_MATERIAL);
  const [lanternX, lanternY, lanternZ] = WAYSTATION_LANTERN;
  const iron = [0.21, 0.19, 0.15];
  mesh.box(lanternX, lanternY, lanternZ, 0.18, 0.24, 0.17, [0.57, 0.46, 0.28], LANTERN_MATERIAL);
  mesh.box(lanternX, lanternY - 0.13, lanternZ, 0.24, 0.055, 0.22, iron);
  mesh.beam([lanternX, lanternY + 0.11, lanternZ], [lanternX, lanternY + 0.22, lanternZ],
    0.15, 0.046, iron, 4);
  mesh.box(lanternX, lanternY + 0.19, (lanternZ + front) * 0.5, 0.055, 0.065, lanternZ - front + 0.06, iron);
  for (const side of [-1, 1]) {
    const windowX = waystationX + side * 1.36;
    mesh.box(windowX, base + 1.30, front, 0.63, 0.71, 0.09, [0.17, 0.19, 0.14]);
    mesh.box(windowX, base + 0.94, front + 0.07, 0.77, 0.12, 0.24, stone, STONE_MATERIAL);
    mesh.box(windowX, base + 1.30, front + 0.06, 0.08, 0.69, 0.06, BARK, BARK_MATERIAL);
  }
  // A framed entrance and a painted pennant distinguish the staffed waystation from a ruin.
  const trim = [0.63, 0.59, 0.47];
  for (const side of [-1, 1]) {
    const x = waystationX + side * 1.94;
    mesh.box(x, base + 1.05, front + 0.08, 0.36, 2.65, 0.43, trim, STONE_MATERIAL);
    mesh.box(x, base + 0.09, front + 0.10, 0.53, 0.30, 0.59, stone, STONE_MATERIAL);
  }
  mesh.box(waystationX, base + 2.29, front + 0.08, 4.28, 0.23, 0.41, trim, STONE_MATERIAL);
  mesh.box(waystationX, base - 0.11, front + 0.59, 1.62, 0.18, 0.77, trim, STONE_MATERIAL);
  const pennantX = waystationX - 1.30;
  const pennantZ = front + 0.35;
  mesh.beam([pennantX - 0.34, base + 2.42, pennantZ], [pennantX + 0.34, base + 2.42, pennantZ],
    0.035, 0.035, BARK, 5, BARK_MATERIAL);
  const banner = [0.58, 0.30, 0.22];
  const left = [pennantX - 0.29, base + 2.37, pennantZ];
  const right = [pennantX + 0.29, base + 2.37, pennantZ];
  const hemLeft = [pennantX - 0.24, base + 1.27, pennantZ + 0.05];
  const hemRight = [pennantX + 0.24, base + 1.27, pennantZ + 0.05];
  const tip = [pennantX, base + 1.03, pennantZ + 0.04];
  mesh.triangle(left, right, hemRight, banner, 5.5);
  mesh.triangle(left, hemRight, hemLeft, banner, 5.5);
  mesh.triangle(hemLeft, hemRight, tip, tint(banner, 0.82), 5.5);

  const roof = [0.56, 0.31, 0.22];
  const a = [waystationX - 2.35, base + 2.52, waystationZ - 2.05];
  const b = [waystationX - 2.35, base + 2.52, waystationZ + 2.05];
  const c = [waystationX, base + 4.12, waystationZ + 2.05];
  const d = [waystationX, base + 4.12, waystationZ - 2.05];
  const e = [waystationX + 2.35, base + 2.52, waystationZ + 2.05];
  const f = [waystationX + 2.35, base + 2.52, waystationZ - 2.05];
  mesh.triangle(b, e, c, stone, STONE_MATERIAL);
  mesh.triangle(a, d, f, stone, STONE_MATERIAL);
  for (const [nearEave, farEave] of [[b, a], [e, f]]) {
    for (let course = 0; course < 7; course++) {
      const p = mix(nearEave, c, course / 7);
      const q = mix(farEave, d, course / 7);
      const r = mix(farEave, d, (course + 1) / 7);
      const s = mix(nearEave, c, (course + 1) / 7);
      const tile = tint(roof, 0.84 + course % 3 * 0.07);
      mesh.triangle(p, q, r, tile, STONE_MATERIAL);
      mesh.triangle(p, r, s, tile, STONE_MATERIAL);
      mesh.beam(p, q, 0.027, 0.027, tint(roof, 0.72), 4, STONE_MATERIAL);
    }
  }
  for (const edge of [[a, b], [b, c], [c, e], [e, f], [f, d], [d, a], [c, d]]) {
    mesh.beam(...edge, 0.09, 0.09, BARK, 4, BARK_MATERIAL);
  }
}

/** Weathered masonry landmark north of camp. The central arch leaves the approach open. */
function buildRuinedGate(mesh) {
  const z = -18;
  const foundation = groundHeight(0, z) - 0.35;
  const masonry = [0.53, 0.53, 0.43];
  const moss = [0.32, 0.40, 0.24];
  const mortar = [0.28, 0.30, 0.23];
  for (const side of [-1, 1]) {
    const x = side * 3.18;
    mesh.box(x, foundation + 0.20, z, 2.28, 0.60, 3.05, tint(masonry, 0.78), STONE_MATERIAL);
    mesh.box(x, foundation + 2.10, z, 1.56, 3.56, 2.05, mortar, STONE_MATERIAL);
    for (let course = 0; course < 6; course++) {
      const y = foundation + 0.72 + course * 0.53;
      const color = tint(masonry, 0.88 + (course * 3 % 5) * 0.06);
      mesh.box(x + side * (course % 2) * 0.035, y, z, 1.61, 0.51, 2.17 + (course % 3) * 0.06,
        color, STONE_MATERIAL);
    }
    mesh.box(x, foundation + 3.53, z, 1.89, 0.23, 2.40, tint(masonry, 1.08), STONE_MATERIAL);
    mesh.box(x, foundation + 3.68, z, 1.73, 0.14, 2.28, tint(masonry, 0.91), STONE_MATERIAL);

    // The two wing walls have different breaks, with staggered blocks over a dark mortar bed.
    const blockHeights = side < 0 ? [6, 6, 4, 3, 2] : [5, 4, 4, 2, 1];
    for (let section = 0; section < blockHeights.length; section++) {
      const wallX = side * (4.48 + section * 1.43);
      const wallY = groundHeight(wallX, z) - 0.37;
      const courses = blockHeights[section];
      const height = courses * 0.43;
      mesh.box(wallX, wallY + height / 2, z, 1.47, height, 1.28, mortar, STONE_MATERIAL);
      for (let course = 0; course < courses; course++) {
        const unevenFace = 0.06 * ((section * 3 + course * 7) % 5);
        const offset = side * ((course % 2) - 0.5) * 0.15;
        const age = 0.74 + ((section * 5 + course * 3) % 7) * 0.045;
        const color = course === 0 || (course === courses - 1 && section > 1)
          ? mix(tint(masonry, age), moss, 0.48) : tint(masonry, age);
        mesh.box(wallX + offset, wallY + (course + 0.5) * 0.43, z,
          1.49, 0.41, 1.37 + unevenFace, color, STONE_MATERIAL);
      }
    }
  }

  const spring = foundation + 3.55;
  const inner = 2.37;
  const outer = 3.30;
  const arc = (angle, radius, depth) => [Math.cos(angle) * radius, spring + Math.sin(angle) * radius, z + depth];
  function wedge(from, to, inside, outside, near, far, color) {
    const faces = [near, far].map(depth => [arc(from, inside, depth), arc(from, outside, depth),
      arc(to, outside, depth), arc(to, inside, depth)]);
    for (const [a, b, c, d] of faces) {
      mesh.triangle(a, b, c, color, STONE_MATERIAL);
      mesh.triangle(a, c, d, color, STONE_MATERIAL);
    }
    for (let edge = 0; edge < 4; edge++) {
      const next = (edge + 1) % 4;
      mesh.triangle(faces[0][edge], faces[1][edge], faces[1][next], color, STONE_MATERIAL);
      mesh.triangle(faces[0][edge], faces[1][next], faces[0][next], color, STONE_MATERIAL);
    }
  }
  // A continuous barrel sits behind the visible joints between the face stones.
  for (let segment = 0; segment < 12; segment++) {
    wedge(segment * Math.PI / 12, (segment + 1) * Math.PI / 12, inner + 0.015, outer - 0.08,
      0.83, -0.83, mortar);
  }
  for (let block = 0; block < 13; block++) {
    const from = block * Math.PI / 13 + 0.006;
    const to = (block + 1) * Math.PI / 13 - 0.006;
    const keystone = block === 6;
    const depth = keystone ? 1.27 : 1.09 + block % 3 * 0.045;
    const edge = outer + (keystone ? 0.20 : (block % 3 - 1) * 0.06);
    const color = tint(masonry, keystone ? 1.22 : 0.88 + (block * 3 % 7) * 0.045);
    wedge(from, to, inner, edge, depth, 0.80, color);
    wedge(from, to, inner, edge, -0.80, -depth, color);
  }
  for (let paving = 0; paving < 10; paving++) {
    const pavingZ = z + 4.1 - paving * 0.84;
    const pavingY = groundHeight(0, pavingZ);
    for (const side of [-1, 1]) {
      mesh.box(side * 1.04, pavingY + 0.025, pavingZ, 1.99, 0.14, 0.77,
        mix(tint(masonry, 0.86 + (paving * 3 % 7) * 0.04), moss, paving % 3 * 0.12), STONE_MATERIAL);
    }
  }
}

function cross(a, b) {
  return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
}

/** Store interleaved world-space positions and baked directional color for WebGL. */
export function meshBuilder(actorArt, appendVertex) {
  const vertices = [];
  function triangle(a, b, c, color, material = 0, coordinates = null, bakedShades) {
    let faceShade;
    let horizontal;
    let side;
    if (!bakedShades) {
      const normal = cross(b.map((value, index) => value - a[index]), c.map((value, index) => value - a[index]));
      const length = Math.hypot(...normal) || 1;
      faceShade = 0.66 + 0.34 * Math.abs((-normal[0] * 0.39 + normal[1] * 0.83 - normal[2] * 0.36) / length);
      const [nx, ny, nz] = normal.map(Math.abs);
      horizontal = ny >= nx && ny >= nz;
      side = nx > nz;
    }
    const colors = Array.isArray(color[0]) ? color : [color, color, color];
    const weights = Array.isArray(material) ? material : [material, material, material];
    [a, b, c].forEach((point, index) => {
      const [x, y, z] = point;
      const uv = coordinates?.[index] || (weights[index] < 2 || horizontal ? [x, z] : side ? [z, y] : [x, y]);
      const shade = bakedShades?.[index] ?? faceShade;
      const [red, green, blue] = colors[index];
      if (appendVertex) appendVertex(x, y, z, red * shade, green * shade, blue * shade, weights[index], uv[0], uv[1]);
      else vertices.push(x, y, z, red * shade, green * shade, blue * shade, weights[index], uv[0], uv[1]);
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
    const growth = 0.5 + 0.5 * Math.sin(angle * 1.87 + x * 0.11);
    const lean = height * (broadleaf ? 0.13 : 0.065) * (0.66 + growth * 0.58);
    const trunkAt = level => {
      const bend = lean * level ** 1.45;
      return [x + Math.cos(angle) * bend, y + height * level, z + Math.sin(angle) * bend];
    };
    const levels = [0, broadleaf ? 0.16 : 0.11, 0.54, 1];
    const radii = broadleaf ? [0.110, 0.058, 0.032, 0.004] : [0.073, 0.046, 0.023, 0.003];
    const sides = 7;
    const bark = broadleaf ? [0.30, 0.28, 0.23] : [0.34, 0.28, 0.21];
    const rootColor = mix(bark, [0.19, 0.29, 0.13], 0.55 + growth * 0.20);
    // The same rings carry the bark across joints and bury each root lobe on sloped ground.
    const rings = levels.map((level, ring) => {
      const [cx, cy, cz] = trunkAt(level);
      return Array.from({ length: sides }, (_, index) => {
        const azimuth = angle + index / sides * TAU;
        const lobe = Math.cos(index * TAU * 3 / sides + angle);
        const radius = height * radii[ring] * (0.91 + growth * 0.16)
          * (1 + lobe * (ring === 0 ? 0.29 : 0.07));
        const px = cx + Math.cos(azimuth) * radius;
        const pz = cz + Math.sin(azimuth) * radius;
        const brightness = 0.70 + 0.30 * Math.max(0, Math.cos(azimuth + 2.38));
        return { point: [px, ring === 0 ? groundHeight(px, pz) - 0.19 : cy, pz],
          color: tint(mix(rootColor, bark, Math.min(1, level * 3.8)), brightness) };
      });
    });
    const repeats = Math.max(1, Math.round(height * (broadleaf ? 0.33 : 0.24)));
    const uvAt = (sector, ring) => [growth + sector / sides * repeats, levels[ring] * height * 0.36];
    for (let ring = 0; ring < rings.length - 1; ring++) {
      for (let sector = 0; sector < sides; sector++) {
        const next = (sector + 1) % sides;
        const a = rings[ring][sector];
        const b = rings[ring][next];
        const c = rings[ring + 1][next];
        const d = rings[ring + 1][sector];
        const auv = uvAt(sector, ring);
        const buv = uvAt(sector + 1, ring);
        const cuv = uvAt(sector + 1, ring + 1);
        const duv = uvAt(sector, ring + 1);
        triangle(a.point, b.point, c.point, [a.color, b.color, c.color], TRUNK_MATERIAL, [auv, buv, cuv], [1, 1, 1]);
        triangle(a.point, c.point, d.point, [a.color, c.color, d.color], TRUNK_MATERIAL, [auv, cuv, duv], [1, 1, 1]);
      }
    }
    if (broadleaf) {
      for (let root = 0; root < 3; root++) {
        const direction = angle + root * TAU / 3 + growth * 0.42;
        const dx = Math.cos(direction);
        const dz = Math.sin(direction);
        const reach = height * (0.17 + 0.07 * Math.cos(direction * 1.7 + z));
        const width = height * 0.036;
        const onSlope = (along, across, rise) => {
          const px = x + dx * along - dz * across;
          const pz = z + dz * along + dx * across;
          return [px, groundHeight(px, pz) + rise, pz];
        };
        const start = height * 0.042;
        const left = onSlope(start, -width, -0.13);
        const right = onSlope(start, width, -0.13);
        const ridge = onSlope(start, 0, height * 0.066);
        const shoulder = onSlope(reach * 0.58, 0, height * 0.028);
        const tip = onSlope(reach, 0, -0.07);
        const lu = [growth, 0.26];
        const ru = [growth + 0.39, 0.26];
        const ridgeUv = [growth + 0.20, 0.42];
        const shoulderUv = [growth + 0.20, 0.62];
        const tipUv = [growth + 0.20, 0.88];
        triangle(left, ridge, shoulder, [rootColor, bark, rootColor], TRUNK_MATERIAL, [lu, ridgeUv, shoulderUv]);
        triangle(ridge, right, shoulder, [bark, rootColor, rootColor], TRUNK_MATERIAL, [ridgeUv, ru, shoulderUv]);
        triangle(left, shoulder, tip, rootColor, TRUNK_MATERIAL, [lu, shoulderUv, tipUv]);
        triangle(shoulder, right, tip, rootColor, TRUNK_MATERIAL, [shoulderUv, ru, tipUv]);
      }
    }
    const canopyCard = (center, size, direction, color, tilt) =>
      foliageCard(center, size, direction, color, tilt, broadleaf);
    for (let branch = 0; branch < 6; branch++) {
      const branchAngle = angle + branch * 2.39996 + Math.sin(branch + angle) * 0.32;
      const level = (broadleaf ? 0.36 : 0.43) + branch * 0.065 + growth * 0.045;
      const spread = height * (broadleaf ? 0.36 - branch * 0.025 : 0.26 - branch * 0.024) * (0.87 + growth * 0.25);
      const trunk = trunkAt(level);
      const radial = (length, rise, direction) => [trunk[0] + Math.cos(direction) * spread * length,
        trunk[1] + height * rise, trunk[2] + Math.sin(direction) * spread * length];
      const fork = radial(0.52, 0.08, branchAngle);
      const tip = radial(1, 0.14, branchAngle);
      const twig = radial(0.84, 0.13, branchAngle + 0.55);
      beam(trunk, fork, height * 0.017, height * 0.009, BARK, 4, BARK_MATERIAL);
      beam(fork, tip, height * 0.009, 0.013, BARK, 3, BARK_MATERIAL);
      const foliage = tint(broadleaf ? [0.70, 0.91, 0.60] : [0.58, 0.85, 0.72], 0.76 + branch * 0.045);
      const clusterSize = height * (broadleaf ? 0.46 : 0.41 - branch * 0.020);
      canopyCard(fork, clusterSize, branchAngle + 0.52, foliage, 0.32);
      canopyCard(tip, clusterSize, branchAngle - 0.70, tint(foliage, 1.12), 0.40);
      canopyCard(tip, clusterSize * 0.90, branchAngle + 0.86, foliage, -0.35);
      canopyCard(twig, clusterSize * 0.88, branchAngle + 0.45, tint(foliage, 1.23), 0.25);
      canopyCard(twig, clusterSize * 0.76, branchAngle + 1.90, foliage);
    }
    const crown = trunkAt(0.83);
    for (let spray = 0; spray < 5; spray++) {
      const size = height * (broadleaf ? 0.29 + spray * 0.015 : 0.24 - spray * 0.012);
      canopyCard(crown, size, angle + spray * 2.39996, [0.67, 0.86, 0.59], 0.23);
    }
  }
  function foliageCard([x, y, z], size, angle, color, tilt = 0, broadleaf = true) {
    const dx = Math.cos(angle) * size * 0.5;
    const dz = Math.sin(angle) * size * 0.5;
    const cardHeight = size * (broadleaf ? 1 : 0.68);
    const a = [x - dx, y - cardHeight * 0.15, z - dz];
    const b = [x + dx, y - cardHeight * 0.15, z + dz];
    const c = [x + dx * 0.78 - dz * tilt, y + cardHeight * 0.65, z + dz * 0.78 + dx * tilt];
    const d = [x - dx * 0.78 - dz * tilt, y + cardHeight * 0.65, z - dz * 0.78 + dx * tilt];
    // Each tile has a four-pixel alpha border around the cutout.
    const tile = broadleaf ? 0 : 256;
    const left = (tile + 4) / 512;
    const right = (tile + 252) / 512;
    const top = 4 / 256;
    const bottom = 252 / 256;
    triangle(a, b, c, color, FOLIAGE_MATERIAL, [[left, bottom], [right, bottom], [right, top]]);
    triangle(a, c, d, color, FOLIAGE_MATERIAL, [[left, bottom], [right, top], [left, top]]);
  }
  function underbrush(x, z, angle) {
    const base = [x, groundHeight(x, z) + 0.14, z];
    for (let stem = 0; stem < 4; stem++) {
      foliageCard(base, 0.85 + stem * 0.11, angle + stem * 1.4, [0.51, 0.74, 0.47], 0.7);
    }
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
  return { vertices, triangle, box, beam, cone, disk, ellipsoid, tree, grass, underbrush, ...actors };
}
