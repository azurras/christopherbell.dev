const POSES = {
  GATHER: ['chop', 1.35], GATHER_STONE: ['mine', 1.35], FISH: ['fish', 2.35],
  BUILD_SHELTER: ['hammer', 1.6], BUILD_BOAT: ['hammer', 1.6],
  CRAFT_CLUB: ['hammer', 1.6], CRAFT_AXE: ['hammer', 1.6], CRAFT_SPEAR: ['hammer', 1.6],
  HUNT: ['aim', 1.05], ENGAGE: ['aim', 0.75], ATTACK: ['strike', 0.7], WEAPON_SKILL: ['strike', 1.1],
  DEFEND: ['brace', 0.9], GUARD: ['brace', 1.25], DISENGAGE: ['lower', 0.65],
  EAT: ['eat', 1.25], REST: ['rest', 1.4], SLEEP: ['rest', 0.8], WAKE: ['wake', 0.9],
  FLEE: ['run', 0.95], ESCAPE: ['row', 1.55], GIVE: ['give', 0.95],
};

/** Convert a confirmed command into a bounded, frame-rate-independent visual pose. */
export function actionPose(gesture) {
  const definition = POSES[gesture?.action];
  if (!definition || !Number.isFinite(gesture.ageSeconds) || gesture.ageSeconds < 0 || gesture.ageSeconds >= definition[1]) {
    return null;
  }
  const [kind, duration] = definition;
  const progress = gesture.ageSeconds / duration;
  const weight = Math.min(1, progress * 9, (1 - progress) * 6);
  return { kind, progress, weight };
}
