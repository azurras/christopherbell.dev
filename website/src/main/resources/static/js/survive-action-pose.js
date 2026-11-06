const POSES = {
  GATHER: ['chop', 1.35], GATHER_STONE: ['mine', 1.35], FISH: ['fish', 2.35],
  BUILD_SHELTER: ['hammer', 1.6], BUILD_BOAT: ['hammer', 1.6],
  CRAFT_CLUB: ['hammer', 1.6], CRAFT_AXE: ['hammer', 1.6], CRAFT_SPEAR: ['hammer', 1.6],
  HUNT: ['aim', 1.05], ENGAGE: ['aim', 0.75], ATTACK: ['strike', 0.7], WEAPON_SKILL: ['strike', 1.1],
  DEFEND: ['brace', 0.9], GUARD: ['brace', 1.25], DISENGAGE: ['lower', 0.65],
  EAT: ['eat', 1.25], REST: ['rest', 1.4], SLEEP: ['rest', 0.8], WAKE: ['wake', 0.9],
  FLEE: ['run', 0.95], ESCAPE: ['row', 1.55], GIVE: ['give', 0.95],
};

/** A settled combat stance remains visible between strikes and with reduced motion. */
export function combatStance(engaged, defending = false) {
  if (!engaged) return null;
  return defending ? { kind: 'brace', progress: 0.7, weight: 1 }
    : { kind: 'ready', progress: 0.5, weight: 0.75 };
}

/** Retain the last observed revision so repeated pulses cannot restart its animation. */
export function acceptRemoteStrike(previous, strike, receivedAtSeconds) {
  if (!strike || !Number.isSafeInteger(strike.revision) || strike.revision <= (previous?.revision ?? 0)) {
    return previous;
  }
  return { ...strike, receivedAtSeconds };
}

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

/** A confirmed incoming hit or consumed guard drives the enemy, independently of the player's pose. */
export function enemyCountered(previous, current) {
  return previous?.status === 'COMBAT' && current?.status === 'COMBAT'
    && previous.survivorId === current.survivorId && previous.enemy === current.enemy
    && current.revision > previous.revision && current.enemyHealth < previous.enemyHealth
    && (current.health < previous.health || previous.defending === true && current.defending === false);
}

/** Wind back, spring forward and return to the encounter position. */
export function enemyCounterPose(ageSeconds) {
  if (!Number.isFinite(ageSeconds) || ageSeconds < 0 || ageSeconds >= 0.76) return null;
  const progress = ageSeconds / 0.76;
  const forward = progress < 0.30 ? -0.19 * Math.sin(progress / 0.30 * Math.PI)
    : 0.61 * Math.sin((progress - 0.30) / 0.70 * Math.PI);
  return { kind: 'strike', progress, weight: Math.max(0, forward / 0.61), forward };
}
