/** Articulated adventurers and species-specific animal silhouettes for the world renderer. */
import { drawActorArt } from './survive-authored-actors.js';

const CLOTH = 5.5;
const LEATHER = 6.5;
const WOLF_FUR = 7.5;
const BROWN_FUR = 8.5;
const TAU = Math.PI * 2;
const tint = (color, brightness) => color.map(channel => channel * brightness);

// Longitudinal sections are [distance forward, half-width, half-height, center height].
const ANIMALS = {
  HOG: {
    color: [0.43, 0.32, 0.26], legWidth: 0.16,
    body: [[-0.99, 0.01, 0.02, 0.82], [-0.80, 0.33, 0.32, 0.83], [-0.49, 0.42, 0.43, 0.87],
      [-0.10, 0.46, 0.47, 0.88], [0.37, 0.39, 0.49, 0.89], [0.72, 0.25, 0.30, 0.83], [0.86, 0.08, 0.12, 0.78]],
    head: [[0.42, 0.23, 0.28, 0.89], [0.73, 0.27, 0.31, 0.81], [0.94, 0.22, 0.25, 0.73],
      [1.15, 0.18, 0.15, 0.67], [1.31, 0.15, 0.095, 0.67]],
    legs: [[-0.61, 0.73, 0.30], [0.45, 0.77, 0.29]], ear: [0.71, 1.05, 0.17],
    eye: [0.91, 0.88, 0.206], nose: [1.34, 0.67, 0.158], tail: [-0.85, 0.91, -1.23, 0.82, 0.045],
  },
  WOLF: {
    color: [0.45, 0.46, 0.45], legWidth: 0.123,
    body: [[-1.08, 0.01, 0.02, 0.86], [-0.84, 0.27, 0.31, 0.87], [-0.45, 0.28, 0.29, 0.89],
      [-0.08, 0.36, 0.36, 0.96], [0.34, 0.37, 0.42, 0.97], [0.68, 0.23, 0.33, 1.04], [0.82, 0.12, 0.15, 1.14]],
    head: [[0.48, 0.14, 0.19, 1.13], [0.74, 0.22, 0.25, 1.16], [0.94, 0.185, 0.17, 1.12],
      [1.15, 0.135, 0.105, 1.03], [1.37, 0.088, 0.075, 1.02]],
    legs: [[-0.74, 0.84, 0.215], [0.40, 0.87, 0.27]], ear: [0.70, 1.35, 0.14],
    eye: [0.94, 1.19, 0.167], nose: [1.39, 1.01, 0.091], tail: [-0.91, 0.96, -1.74, 0.42, 0.142],
  },
  BEAR: {
    color: [0.37, 0.255, 0.18], legWidth: 0.215,
    body: [[-1.02, 0.01, 0.02, 0.94], [-0.81, 0.40, 0.43, 0.94], [-0.43, 0.51, 0.49, 0.96],
      [0.02, 0.55, 0.55, 1.00], [0.41, 0.49, 0.62, 1.03], [0.71, 0.36, 0.46, 1.00], [0.92, 0.16, 0.24, 0.98]],
    head: [[0.56, 0.18, 0.27, 1.02], [0.77, 0.30, 0.33, 1.04], [0.98, 0.27, 0.28, 0.99],
      [1.18, 0.17, 0.17, 0.91], [1.30, 0.125, 0.11, 0.91]],
    legs: [[-0.65, 0.83, 0.36], [0.47, 0.86, 0.34]], ear: [0.76, 1.33, 0.215],
    eye: [1.04, 1.10, 0.215], nose: [1.33, 0.92, 0.134], tail: [-0.92, 1.02, -1.19, 0.94, 0.084],
  },
};

export function createActorModels({ triangle, beam, ellipsoid, groundHeight, actorArt }) {
  function groundShadow(x, z, length, width) {
    const sides = 18;
    const point = (radius, angle) => {
      const dx = Math.cos(angle) * length * radius;
      const dz = Math.sin(angle) * width * radius;
      const worldX = x + length * 0.31 + dx * 0.8 - dz * 0.6;
      const worldZ = z + length * 0.24 + dx * 0.6 + dz * 0.8;
      return [worldX, groundHeight(worldX, worldZ) + 0.067, worldZ];
    };
    const center = point(0, 0);
    const color = [0.10, 0.12, 0.10];
    for (let side = 0; side < sides; side++) {
      const angle = side / sides * TAU;
      const next = (side + 1) / sides * TAU;
      const a = point(0.48, angle);
      const b = point(0.48, next);
      const c = point(1, next);
      const d = point(1, angle);
      triangle(center, a, b, color, 9.5, [[0, 0], [0.27, 0], [0.27, 0]]);
      triangle(a, c, b, color, 9.5, [[0.27, 0], [1, 0], [0.27, 0]]);
      triangle(a, d, c, color, 9.5, [[0.27, 0], [1, 0], [1, 0]]);
    }
  }
  function profile(pose, rings, color, material, sides) {
    const skin = rings.map(([height, width, depth, offset = 0], row) =>
      Array.from({ length: sides + 1 }, (_, column) => {
        const u = column / sides;
        const angle = u * TAU;
        const across = Math.cos(angle);
        const forward = Math.sin(angle);
        return { position: pose(across * width, height, offset + forward * depth),
          shade: tint(color, 0.88 + 0.17 * forward - 0.07 * across), uv: [u, 1 - row / (rings.length - 1)] };
      }));
    function face(a, b, c) {
      triangle(a.position, b.position, c.position, [a.shade, b.shade, c.shade], material, [a.uv, b.uv, c.uv]);
    }
    for (let row = 1; row < skin.length; row++) for (let side = 0; side < sides; side++) {
      const a = skin[row - 1][side];
      const b = skin[row][side];
      const c = skin[row][side + 1];
      const d = skin[row - 1][side + 1];
      face(a, b, c);
      face(a, c, d);
    }
  }

  function avatar(x, z, heading, cloth, status, stride = 0, weapon, action = null, detailed = true, seconds = 0,
      color, keeper = false) {
    const y = groundHeight(x, z);
    const resting = status === 'SLEEPING' || status === 'DEAD' || (status === 'ESCAPED' && action?.kind !== 'row');
    const cos = Math.cos(heading);
    const sin = Math.sin(heading);
    const crouch = action?.kind === 'rest' ? action.weight * 0.38 : 0;
    const pose = (across, height, forward = 0) => resting
      ? [x + across * cos + height * sin, y + 0.31 + forward, z - across * sin + height * cos]
      : [x + across * cos + forward * sin, y + height + (0.12 - crouch) * Math.min(1, height), z - across * sin + forward * cos];
    const headPose = (across, height, forward = 0) => pose(across * 0.87, 2.12 + (height - 2.12) * 0.84, forward * 0.91);
    const leather = [0.34, 0.21, 0.16];
    const trousers = [0.23, 0.25, 0.27];
    const skin = [0.72, 0.55, 0.42];
    const hardware = [0.58, 0.55, 0.43];
    const part = (at, width, height, depth, color, material = 0) =>
      ellipsoid(...at, width, resting ? depth : height, resting ? height : depth, color, detailed ? 10 : 6, 4, heading, material);
    groundShadow(x, z, 1.08, 0.48);
    if (keeper) {
      const wool = [0.37, 0.49, 0.36];
      profile(pose, [[1.01, 0.45, 0.34], [1.37, 0.39, 0.30], [1.81, 0.43, 0.31],
        [2.01, 0.52, 0.32], [2.15, 0.26, 0.24]], wool, CLOTH, 12);
      profile(pose, [[2.42, 0.31, 0.30], [2.50, 0.29, 0.28], [2.66, 0.22, 0.24],
        [2.69, 0.06, 0.06]], [0.42, 0.28, 0.18], LEATHER, 12);
      part(pose(0, 1.94, 0.33), 0.09, 0.11, 0.035, [0.74, 0.63, 0.36]);
    }
    const authored = detailed && actorArt?.adventurer;
    if (authored) {
      const gesture = !resting && action?.kind !== 'run' && authored.poses[action?.kind];
      const motion = action?.kind === 'run' ? 'run' : 'walk';
      const clip = gesture ? action.kind : (stride !== 0 ? motion : 'idle');
      const progress = gesture ? action.progress : seconds * { idle: 0.44, walk: 1.7, run: 2.6 }[clip];
      const hand = drawActorArt(triangle, authored, pose, clip, progress, !gesture, color);
      drawEquipment(hand);
      return;
    }
    const breath = action?.kind === 'sleep' ? action.weight * 0.035 : 0;
    profile(pose, [[1.11, 0.32, 0.23], [1.34, 0.30, 0.22], [1.58, 0.40, 0.24],
      [1.79, 0.46, 0.26, breath], [1.91, 0.38, 0.23], [2.02, 0.18, 0.15]], cloth, CLOTH, detailed ? 16 : 8);
    profile(pose, [[0.93, 0.40, 0.29], [1.10, 0.38, 0.28], [1.32, 0.31, 0.24]], [0.47, 0.30, 0.22], LEATHER, detailed ? 14 : 8);
    profile(pose, [[1.24, 0.33, 0.25], [1.33, 0.34, 0.25]], leather, LEATHER, 12);
    const strap = (front, side) => {
      const a = pose(-0.35 * side, 1.87, front * 0.25);
      const b = pose(-0.23 * side, 1.83, front * 0.27);
      const c = pose(0.31 * side, 1.30, front * 0.252);
      const d = pose(0.22 * side, 1.27, front * 0.26);
      triangle(a, b, c, leather, LEATHER, [[0, 0], [1, 0], [1, 1]]);
      triangle(a, c, d, leather, LEATHER, [[0, 0], [1, 1], [0, 1]]);
    };
    strap(-1, 1);
    strap(1, -1);
    part(pose(0, 1.29, 0.28), 0.095, 0.07, 0.05, hardware);
    part(pose(-0.27, 1.20, -0.20), 0.17, 0.21, 0.12, leather, LEATHER);
    part(pose(0.22, 1.28, -0.21), 0.10, 0.13, 0.08, hardware);
    part(pose(0, 2.12), 0.13, 0.23, 0.13, skin);
    part(pose(0, 2.04), 0.25, 0.10, 0.20, [0.26, 0.27, 0.24]);
    profile(headPose, [[2.10, 0.125, 0.12, 0.04], [2.19, 0.16, 0.17, 0.035], [2.31, 0.205, 0.185, 0.02],
      [2.43, 0.196, 0.18], [2.54, 0.13, 0.12], [2.59, 0.01, 0.01]], skin, 0, detailed ? 14 : 8);
    profile(headPose, [[2.26, 0.19, 0.14, -0.105], [2.38, 0.214, 0.20, -0.055],
      [2.51, 0.195, 0.18, -0.023], [2.60, 0.12, 0.115, -0.025], [2.63, 0.01, 0.01]], [0.18, 0.125, 0.095], 0, 12);
    part(headPose(0, 2.29, 0.225), 0.057, 0.053, 0.047, skin);
    let toolHand;
    for (const side of [-1, 1]) {
      const swing = resting ? 0 : stride * side;
      const knee = pose(side * 0.19, 0.61 + Math.max(0, swing) * 0.19, swing * 0.34);
      const ankle = pose(side * 0.20, 0.16 + Math.max(0, swing) * 0.16, swing * 0.58);
      beam(pose(side * 0.19, 1.15), knee, 0.169, 0.115, trousers, 10, CLOTH);
      beam(knee, ankle, 0.129, 0.105, leather, 10, LEATHER);
      part(pose(side * 0.19, 0.54 + Math.max(0, swing) * 0.17, swing * 0.36), 0.17, 0.07, 0.145, [0.51, 0.37, 0.25]);
      part(pose(side * 0.20, 0.13 + Math.max(0, swing) * 0.16, swing * 0.58 + 0.10), 0.13, 0.115, 0.25, leather, LEATHER);
      const reach = resting ? null : actionReach(action, side);
      const elbow = pose(side * 0.51, 1.48 + (reach?.lift ?? 0) * 0.48,
        -swing * 0.29 + (reach?.forward ?? 0) * 0.45);
      const wrist = pose(side * 0.55 - (reach?.inward ?? 0) * side, 1.14 + (reach?.lift ?? 0),
        0.09 - swing * 0.42 + (reach?.forward ?? 0));
      part(pose(side * 0.44, 1.82), 0.177, 0.14, 0.19, [0.62, 0.58, 0.50]);
      beam(pose(side * 0.44, 1.78), elbow, 0.129, 0.104, [0.62, 0.52, 0.38], 10, CLOTH);
      beam(elbow, wrist, 0.104, 0.077, leather, 10, LEATHER);
      part(wrist, 0.082, 0.112, 0.077, leather, LEATHER);
      if (detailed) {
        part(headPose(side * 0.19, 2.29, 0.015), 0.034, 0.064, 0.041, skin);
        part(headPose(side * 0.075, 2.35, 0.185), 0.027, 0.011, 0.012, [0.16, 0.115, 0.10]);
      }
      if (side === 1) toolHand = wrist;
    }
    drawEquipment(toolHand);

    function drawEquipment(toolHand) {
      const tool = resting ? null : action?.kind;
      if (['chop', 'mine', 'hammer', 'fish', 'row'].includes(tool) || (tool === 'strike' && weapon)) {
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
          drawToolHead(triangle, beam, tip, heading, tool, weapon, hardware);
        }
      } else if (tool === 'eat' || tool === 'give') {
        ellipsoid(...toolHand, 0.13, 0.09, 0.13, [0.65, 0.45, 0.23], 6, 3);
      }
      if (weapon && tool !== 'strike') {
        const length = weapon === 'SPEAR' ? 2.8 : 1.65;
        const haft = pose(0.32, 0.58, -0.36);
        const tip = pose(-0.35, 0.58 + length, -0.37);
        beam(haft, tip, 0.055, 0.044, [0.43, 0.30, 0.17], 5);
        drawToolHead(triangle, beam, tip, heading, 'strike', weapon, hardware);
      }
    }
  }
  function animal(x, z, kind, scale = 1, heading = 0, stride = 0, recoil = 0, detailed = true, seconds = 0) {
    const y = groundHeight(x, z);
    const model = ANIMALS[kind] || ANIMALS.HOG;
    const sin = Math.sin(heading);
    const cos = Math.cos(heading);
    const bob = Math.abs(stride) * 0.04;
    const p = (forward, height, across = 0) => [x + ((forward - recoil * 0.22) * sin + across * cos) * scale,
      y + height * scale, z + ((forward - recoil * 0.22) * cos - across * sin) * scale];
    const axis = (across, forward, height) => p(forward, height + bob, across);
    const fur = model.color;
    const material = kind === 'WOLF' ? WOLF_FUR : BROWN_FUR;
    const sides = detailed ? 14 : 8;
    const part = (point, width, height, depth, color, texture = 0) =>
      ellipsoid(...p(...point), width * scale, height * scale, depth * scale, color, detailed ? 9 : 6, 4, heading, texture);
    groundShadow(x, z, 0.92 * scale, 0.46 * scale);
    const authored = actorArt?.[kind === 'HOG' ? 'boar' : kind?.toLowerCase()];
    if (authored) {
      const clip = stride !== 0 ? 'walk' : 'idle';
      drawActorArt(triangle, authored, (across, height, forward) => p(forward, height, across),
        clip, seconds * 1.55, true);
      return;
    }
    profile(axis, model.body, fur, material, sides);
    profile(axis, model.head, tint(fur, 1.13), material, sides);
    for (const side of [-1, 1]) {
      for (const [hip, height, width] of model.legs) {
        const swing = stride * side * Math.sign(hip);
        const across = width * side;
        const knee = p(hip - 0.10 + swing * 0.15, height * 0.51, across);
        const ankle = p(hip - 0.12 + swing * 0.31, 0.14 + Math.max(0, swing) * 0.12, across);
        beam(p(hip, height + bob, across * 0.91), knee, model.legWidth * scale, model.legWidth * 0.69 * scale, fur, 9, material);
        beam(knee, ankle, model.legWidth * 0.70 * scale, model.legWidth * 0.48 * scale, tint(fur, 0.73), 8, material);
        part([hip - 0.05 + swing * 0.31, 0.09 + Math.max(0, swing) * 0.12, across], model.legWidth * 0.64,
          0.09, model.legWidth * 0.98, [0.22, 0.18, 0.15]);
      }
      const [earX, earY, earZ] = model.ear;
      if (kind === 'BEAR') {
        part([earX, earY, earZ * side], 0.12, 0.14, 0.095, fur, material);
        part([earX + 0.065, earY, earZ * side], 0.07, 0.085, 0.042, [0.37, 0.25, 0.21]);
      } else {
        const a = p(earX - 0.15, earY - 0.09, earZ * side);
        const b = p(earX + 0.15, earY - 0.07, earZ * side);
        const c = p(earX + (kind === 'HOG' ? 0.11 : -0.07), earY + 0.22, (earZ + 0.13) * side);
        triangle(a, b, c, fur, material, [[0, 1], [1, 1], [0.5, 0]]);
        triangle(p(earX - 0.08, earY - 0.035, (earZ + 0.017) * side), b, c, tint(fur, 1.18));
      }
      const [eyeX, eyeY, eyeZ] = model.eye;
      part([eyeX, eyeY, eyeZ * side], 0.031, 0.036, 0.045, [0.19, 0.14, 0.10]);
      if (detailed) part([eyeX + 0.018, eyeY + 0.009, (eyeZ + 0.027) * side], 0.014, 0.014, 0.014, [0.76, 0.56, 0.26]);
      if (kind === 'HOG') {
        const tusk = p(1.12, 0.57, 0.22 * side);
        const bend = p(1.28, 0.59, 0.26 * side);
        const ivory = [0.78, 0.72, 0.57];
        beam(tusk, bend, 0.054 * scale, 0.034 * scale, ivory, 7);
        beam(bend, p(1.35, 0.72, 0.26 * side), 0.035 * scale, 0.005, ivory, 6);
      }
    }
    const [noseX, noseY, noseWidth] = model.nose;
    part([noseX, noseY, 0], noseWidth, kind === 'HOG' ? 0.11 : 0.082, 0.065,
      kind === 'HOG' ? [0.45, 0.32, 0.28] : [0.14, 0.115, 0.106]);
    if (kind === 'HOG') for (const side of [-1, 1]) {
      part([noseX + 0.05, noseY + 0.019, 0.071 * side], 0.027, 0.022, 0.025, [0.22, 0.15, 0.14]);
    }
    const [tailX, tailY, tipX, tipY, tailWidth] = model.tail;
    const tailBend = p((tailX + tipX) / 2, (tailY + tipY) / 2, stride * 0.06);
    beam(p(tailX, tailY), tailBend, tailWidth * scale, tailWidth * 0.66 * scale, fur, 9, material);
    beam(tailBend, p(tipX, tipY, stride * 0.14), tailWidth * 0.67 * scale, 0.016 * scale, fur, 8, material);
  }
  return { avatar, animal };
}

function drawToolHead(triangle, beam, tip, heading, tool, weapon, metal) {
  const sin = Math.sin(heading);
  const cos = Math.cos(heading);
  const p = (side, lift, forward) => [tip[0] + side * cos + forward * sin, tip[1] + lift, tip[2] - side * sin + forward * cos];
  const kind = tool === 'strike' ? weapon === 'SPEAR' ? 'spear' : weapon === 'STONE_AXE' ? 'chop' : 'club' : tool;
  if (kind === 'club') {
    if (weapon) beam(p(0, -0.26, 0), p(0, 0.20, 0.05), 0.085, 0.12, [0.45, 0.31, 0.19], 8, LEATHER);
    return;
  }
  if (kind === 'mine' || kind === 'hammer') {
    const heavy = kind === 'hammer';
    const center = p(0, 0.04, 0);
    beam(p(-0.26, heavy ? 0.04 : -0.08, 0), center, heavy ? 0.13 : 0.014, 0.095, metal, heavy ? 4 : 6);
    beam(center, p(0.25, heavy ? 0.04 : -0.08, 0), 0.096, heavy ? 0.13 : 0.018, metal, heavy ? 4 : 6);
    return;
  }
  const silhouette = kind === 'spear' ? [[0, 0.36], [0.11, 0.10], [0, -0.12], [-0.11, 0.10]]
    : kind === 'row' ? [[-0.15, 0.31], [0.15, 0.31], [0.09, -0.13], [-0.09, -0.13]]
      : [[0.02, 0.11], [0.33, 0.21], [0.37, -0.12], [0.02, -0.13]];
  const color = kind === 'row' ? [0.57, 0.41, 0.26] : metal;
  for (const face of [-1, 1]) {
    const corners = silhouette.map(([side, height]) => p(side, height, face * 0.023));
    const center = p(kind === 'chop' ? 0.14 : 0, 0.08, face * 0.066);
    for (let edge = 0; edge < corners.length; edge++) {
      triangle(corners[edge], corners[(edge + 1) % corners.length], center, tint(color, edge % 2 ? 0.86 : 1.12));
    }
  }
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
