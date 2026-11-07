/** Detailed display meshes for buildable structures and interactive resource sites. */
const TIMBER = [0.43, 0.30, 0.20];
const CANVAS = [0.65, 0.56, 0.39];
const WOOD = 3.5;
const CLOTH = 5.5;

function panel(mesh, a, b, c, d, color, material = 0) {
  mesh.triangle(a, b, c, color, material, [[0, 0], [1, 0], [1, 1]]);
  mesh.triangle(a, c, d, color, material, [[0, 0], [1, 1], [0, 1]]);
}

export function drawShelter(mesh, x, y, z) {
  const p = (across, height, forward) => [x + across, y + height, z + forward];
  for (const side of [-1, 1]) {
    panel(mesh, p(0, 2.56, -1.64), p(0, 2.56, 1.58), p(side * 1.57, 0.25, 1.83),
      p(side * 1.57, 0.25, -1.73), side === 1 ? CANVAS : [0.54, 0.46, 0.34], CLOTH);
    mesh.beam(p(side * 1.56, 0.25, 1.84), p(0, 2.59, 1.58), 0.037, 0.029, [0.37, 0.32, 0.24], 5);
    mesh.beam(p(side * 1.57, 0.26, 1.52), p(side * 2.0, 0.05, 2.1), 0.018, 0.014, [0.45, 0.38, 0.27], 4);
    mesh.beam(p(side * 2.0, 0, 2.1), p(side * 2.0, 0.22, 2.1), 0.055, 0.041, TIMBER, 5, WOOD);
    mesh.triangle(p(0, 2.48, 1.54), p(side * 1.56, 0.25, 1.82), p(side * 0.42, 0.25, 1.72), CANVAS, CLOTH,
      [[0.5, 0], [1, 1], [0.65, 1]]);
  }
  mesh.triangle(p(0, 2.55, -1.64), p(1.53, 0.25, -1.73), p(-1.53, 0.25, -1.73),
    [0.49, 0.42, 0.29], CLOTH, [[0.5, 0], [1, 1], [0, 1]]);
  mesh.beam(p(0, 2.55, -1.77), p(0, 2.55, 1.75), 0.055, 0.048, TIMBER, 6, WOOD);
  mesh.box(x, y + 0.18, z, 2.9, 0.24, 3.5, [0.36, 0.28, 0.20]);
}

export function drawBoat(mesh, x, z) {
  const sections = [[-2.7, 0.04], [-1.9, 0.78], [-0.9, 1.03], [0.9, 1.04], [1.94, 0.75], [2.75, 0.04]];
  const bands = [[-0.32, 0.40], [-0.06, 0.75], [0.20, 0.98], [0.46, 1.0]];
  const shades = [[0.26, 0.18, 0.13], [0.37, 0.24, 0.16], [0.48, 0.33, 0.22]];
  const point = (section, band, side) => [x + sections[section][1] * bands[band][1] * side,
    bands[band][0] + Math.abs(sections[section][0]) ** 2 * 0.040, z + sections[section][0]];
  for (const side of [-1, 1]) {
    for (let section = 0; section < sections.length - 1; section++) {
      for (let band = 0; band < bands.length - 1; band++) {
        panel(mesh, point(section, band, side), point(section + 1, band, side),
          point(section + 1, band + 1, side), point(section, band + 1, side), shades[band], WOOD);
      }
      mesh.beam(point(section, 3, side), point(section + 1, 3, side), 0.069, 0.069, [0.60, 0.44, 0.28], 6, WOOD);
    }
  }
  mesh.box(x, 0.04, z, 1.3, 0.12, 3.1, TIMBER, WOOD);
  for (const forward of [-1.36, 0.32, 1.31]) {
    mesh.box(x, 0.37, z + forward, 1.58, 0.12, 0.29, [0.56, 0.42, 0.29], WOOD);
  }
  mesh.beam([x - 0.69, 0.48, z - 1.22], [x + 0.69, 0.60, z + 1.61], 0.052, 0.036, TIMBER, 7);
  mesh.ellipsoid(x + 0.70, 0.60, z + 1.63, 0.12, 0.035, 0.34, [0.54, 0.36, 0.21], 7, 3, 0.46);
}

export function drawQuarryRock(mesh, x, y, z, size, angle, accent) {
  const sides = 7;
  const rings = [[0.02, 0.91], [0.36, 1], [0.80, 0.59]];
  const points = rings.map(([height, radius], row) => Array.from({ length: sides }, (_, index) => {
    const direction = angle + index / sides * Math.PI * 2;
    const edge = radius * (0.77 + (index * 11 + row * 3) % 7 * 0.055);
    return [x + Math.cos(direction) * size * edge, y + size * (height + (index % 3) * 0.045),
      z + Math.sin(direction) * size * edge * 0.79];
  }));
  for (let index = 0; index < sides; index++) {
    const next = (index + 1) % sides;
    const shale = [0.34 + index % 3 * 0.043, 0.36 + index % 3 * 0.035, 0.33 + index % 3 * 0.04];
    for (let row = 0; row < 2; row++) {
      panel(mesh, points[row][index], points[row][next], points[row + 1][next], points[row + 1][index], shale);
    }
    mesh.triangle(points[2][index], points[2][next], [x + size * 0.12, y + size * 0.91, z], shale);
    if (accent && index % 2 === 0) {
      const a = points[2][index];
      const b = points[2][next];
      const inward = p => [p[0] * 0.75 + x * 0.25, p[1] + size * 0.06, p[2] * 0.75 + z * 0.25];
      panel(mesh, a.map((value, coordinate) => coordinate === 1 ? value + 0.012 : value),
        b.map((value, coordinate) => coordinate === 1 ? value + 0.012 : value),
        inward(b), inward(a), [0.52, 0.49, 0.42]);
    }
  }
}

export function addResourceProps(mesh, groundHeight) {
  const woodY = groundHeight(-22, -13);
  mesh.beam([-22, woodY, -13], [-22, woodY + 0.64, -13], 0.58, 0.43, TIMBER, 11, WOOD);
  mesh.disk(-22, woodY + 0.65, -13, 0.42, [0.63, 0.47, 0.29], 12);
  mesh.disk(-22, woodY + 0.652, -13, 0.27, [0.54, 0.37, 0.22], 12);
  for (let log = 0; log < 4; log++) {
    const x = -22.9 - log % 2 * 0.39;
    const y = woodY + 0.17 + Math.floor(log / 2) * 0.26;
    mesh.beam([x, y, -12.9], [x - 0.43, y, -14.32], 0.17, 0.13, TIMBER, 8, WOOD);
  }
  for (const [x, z] of [[-4.45, 3.95], [2.62, -2.55], [-14.5, 72.1]]) {
    const y = groundHeight(x, z);
    mesh.beam([x, y, z], [x, y + 0.77, z], 0.31, 0.36, TIMBER, 10, WOOD);
    mesh.disk(x, y + 0.78, z, 0.35, [0.55, 0.42, 0.28], 10);
    for (const height of [0.16, 0.62]) {
      mesh.beam([x, y + height, z], [x, y + height + 0.07, z], 0.36, 0.36, [0.27, 0.26, 0.24], 10);
    }
  }
}

/** Compose the fishing approach around the open walking and casting space. */
export function addFishingBank(mesh, groundHeight) {
  const stone = [0.40, 0.43, 0.35];
  for (const [x, z, width, height, depth, angle] of [
    [-18.2, 72.1, 1.35, 0.61, 0.83, 0.6], [-17.1, 73.9, 0.83, 0.44, 0.68, 1.9],
    [-9.0, 72.9, 1.14, 0.52, 0.80, 0.2], [-8.0, 74.2, 0.71, 0.34, 0.59, 1.5],
    [-18.9, 70.3, 0.62, 0.31, 0.47, 2.4], [-7.3, 71.8, 0.55, 0.29, 0.48, 0.9],
  ]) {
    const y = groundHeight(x, z);
    mesh.ellipsoid(x, y + height * 0.26, z, width, height, depth, stone, 9, 4, angle, 2.5);
  }
  // A bleached trunk and fork sit beyond the deck rather than across its approach.
  const base = groundHeight(-17.5, 70.1);
  mesh.beam([-19.3, base + 0.22, 70.8], [-16.9, base + 0.30, 70.2], 0.20, 0.14,
    [0.53, 0.47, 0.36], 9, WOOD);
  mesh.beam([-18.1, base + 0.27, 70.5], [-18.6, base + 0.51, 69.8], 0.11, 0.024,
    [0.53, 0.47, 0.36], 7, WOOD);
  for (const [x, z] of [[-18.4, 69.5], [-16.5, 72.5], [-16.9, 75], [-9.3, 70.8], [-8.8, 73.8], [-7.6, 70.1]]) {
    const y = groundHeight(x, z);
    for (let stem = 0; stem < 5; stem++) {
      const angle = stem * 2.39996 + x;
      const sx = x + Math.cos(angle) * 0.28;
      const sz = z + Math.sin(angle) * 0.28;
      const height = 0.88 + stem % 3 * 0.21;
      const tip = [sx + Math.cos(angle) * 0.16, y + height, sz + Math.sin(angle) * 0.16];
      mesh.beam([sx, y - 0.02, sz], tip, 0.018, 0.009, [0.41, 0.44, 0.23], 4);
      if (stem % 2 === 0) mesh.ellipsoid(tip[0], tip[1], tip[2], 0.042, 0.15, 0.042,
        [0.39, 0.28, 0.16], 5, 3);
      for (const side of [-1, 1]) {
        const dx = Math.cos(angle + side * 0.7);
        const dz = Math.sin(angle + side * 0.7);
        const root = [sx, y + 0.06, sz];
        const bend = [sx + dx * 0.30, y + height * 0.69, sz + dz * 0.30];
        const edge = [bend[0] - dz * 0.035, bend[1], bend[2] + dx * 0.035];
        const end = [sx + dx * 0.67, y + height * 0.41, sz + dz * 0.67];
        mesh.triangle(root, bend, edge, [0.37, 0.42, 0.20]);
        mesh.triangle(bend, end, edge, [0.48, 0.47, 0.25]);
      }
    }
  }
}

/** Face the camera with a two-triangle surface for the flame and embers. */
export function drawCampFlame(mesh, yaw) {
  const dx = Math.cos(yaw) * 0.87;
  const dz = -Math.sin(yaw) * 0.87;
  panel(mesh, [4 - dx, 0.27, -dz], [4 + dx, 0.27, dz],
    [4 + dx, 2.40, dz], [4 - dx, 2.40, -dz], [1, 1, 1], 10.5);
}
