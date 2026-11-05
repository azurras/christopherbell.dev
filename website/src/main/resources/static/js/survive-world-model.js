/** Display coordinates in island metres; Java independently enforces every spatial rule. */
export const LANDMARKS = [
  { name: 'Camp', x: 0, z: 0, radius: 15, actions: ['BUILD_SHELTER', 'REST', 'GUARD', 'SLEEP', 'CRAFT_CLUB', 'CRAFT_AXE', 'CRAFT_SPEAR'] },
  { name: 'Pine forest', x: -24, z: -15, radius: 13, actions: ['GATHER'] },
  { name: 'Stone ridge', x: 30, z: -12, radius: 12, actions: ['GATHER_STONE'] },
  { name: 'Hunting grounds', x: -36, z: 25, radius: 13, actions: ['HUNT'] },
  { name: 'Boat shore', x: 0, z: 62, radius: 13, actions: ['BUILD_BOAT', 'ESCAPE'] },
];
export const ACTION_LABELS = {
  GATHER: 'Gather wood', GATHER_STONE: 'Gather stone', HUNT: 'Hunt', BUILD_SHELTER: 'Build shelter',
  BUILD_BOAT: 'Build boat', ESCAPE: 'Sail away', REST: 'Rest', SLEEP: 'Sleep', GUARD: 'Stand guard',
  CRAFT_CLUB: 'Craft club', CRAFT_AXE: 'Craft axe', CRAFT_SPEAR: 'Craft spear', EAT: 'Eat food',
  ATTACK: 'Attack', DEFEND: 'Defend', FLEE: 'Flee to camp', WAKE: 'Wake up',
  SELECT_TARGET: 'Select target', CHECK_TARGET: 'Check', ENGAGE: 'Engage',
  DISENGAGE: 'Disengage', WEAPON_SKILL: 'Weapon skill',
};

/** The island's target menu reveals commands in the order the player can use them. */
export function targetActionsFor(state, selected) {
  if (state.nextStrikeAt) {
    return ['WEAPON_SKILL', 'DEFEND', 'DISENGAGE', 'FLEE'].filter(action => state.actions.includes(action));
  }
  if (!selected) return ['SELECT_TARGET', 'FLEE'];
  return ['CHECK_TARGET', 'ENGAGE', 'FLEE'];
}
const PORTABLE_ACTIONS = ['EAT', 'ATTACK', 'DEFEND', 'FLEE', 'WAKE'];

/** Keep an encounter in the follow camera's forward view even when the survivor faces the camera. */
export function combatPosition(position, yaw) {
  return {
    x: position.x - Math.sin(yaw) * 3.5 + Math.cos(yaw) * 2.5,
    z: position.z - Math.cos(yaw) * 3.5 - Math.sin(yaw) * 2.5,
  };
}

/** Camera-relative movement at six metres per second, normalized before the island boundary. */
export function advancePosition(position, right, forward, yaw, seconds) {
  const length = Math.hypot(right, forward);
  if (!length) return { ...position };
  const distance = 6 * Math.max(0, seconds) / Math.max(1, length);
  const dx = (right * Math.cos(yaw) - forward * Math.sin(yaw)) * distance;
  const dz = (-right * Math.sin(yaw) - forward * Math.cos(yaw)) * distance;
  const x = position.x + dx;
  const z = position.z + dz;
  const boundaryScale = Math.min(1, 78 / Math.hypot(x, z));
  return { x: x * boundaryScale, z: z * boundaryScale, heading: Math.atan2(dx, dz) };
}

/** Only actions already offered by Java may be presented at the matching landmark. */
export function actionsAt(position, allowedActions) {
  return allowedActions.filter(action => PORTABLE_ACTIONS.includes(action) || LANDMARKS.some(landmark =>
    landmark.actions.includes(action) && Math.hypot(position.x - landmark.x, position.z - landmark.z) <= landmark.radius));
}

/** Column-major perspective/view matrix for the third-person follow camera. */
export function cameraMatrix(position, yaw, aspectRatio, elevation = 0) {
  const eye = [position.x + Math.sin(yaw) * 12, elevation + 6.7, position.z + Math.cos(yaw) * 12];
  const back = normalize([eye[0] - position.x, 5.4, eye[2] - position.z]);
  const right = normalize([back[2], 0, -back[0]]);
  const up = [back[1] * right[2], back[2] * right[0] - back[0] * right[2], -back[1] * right[0]];
  const dotEye = axis => -(axis[0] * eye[0] + axis[1] * eye[1] + axis[2] * eye[2]);
  const view = [right[0], up[0], back[0], 0, right[1], up[1], back[1], 0,
    right[2], up[2], back[2], 0, dotEye(right), dotEye(up), dotEye(back), 1];
  const focalLength = 1 / Math.tan(Math.PI / 6);
  const near = 0.2;
  const far = 400;
  const projection = [focalLength / Math.max(0.1, aspectRatio), 0, 0, 0, 0, focalLength, 0, 0,
    0, 0, (far + near) / (near - far), -1, 0, 0, 2 * far * near / (near - far), 0];
  const matrix = new Float32Array(16);
  for (let column = 0; column < 4; column++) {
    for (let row = 0; row < 4; row++) {
      for (let index = 0; index < 4; index++) matrix[column * 4 + row] += projection[index * 4 + row] * view[column * 4 + index];
    }
  }
  return matrix;
}

/** Project a world point into clip space for text labels sharing the rendered camera. */
export function projectPoint(matrix, point) {
  const component = row => matrix[row] * point[0] + matrix[row + 4] * point[1] + matrix[row + 8] * point[2] + matrix[row + 12];
  const w = component(3);
  const x = component(0) / w;
  const y = component(1) / w;
  const z = component(2) / w;
  return { x, y, visible: w > 0 && Math.abs(x) < 1 && Math.abs(y) < 1 && Math.abs(z) < 1 };
}

function normalize(vector) {
  const length = Math.hypot(...vector);
  return vector.map(component => component / length);
}
