/** Server-confirmed outcomes only: rendering never awards resources or predicts combat damage. */
import { ACTION_LABELS } from './survive-world-model.js';

const ENEMIES = { HOG: 'Wild hog', WOLF: 'Grey wolf', BEAR: 'Brown bear' };
const ENEMY_HEALTH = { HOG: 5, WOLF: 8, BEAR: 14 };

/** Compare one survivor's monotonically increasing revisions, excluding initial/resumed inventories. */
export function worldChangesBetween(before, after) {
  if (!before || !after || before.survivorId !== after.survivorId || !(after.revision > before.revision)) return null;
  const numbers = [];
  for (const [field, label] of [['wood', 'wood'], ['stone', 'stone'], ['food', 'food'], ['health', 'health'], ['hunger', 'fullness']]) {
    const delta = after[field] - before[field];
    if (Number.isFinite(delta) && delta !== 0) {
      numbers.push({ text: `${delta > 0 ? '+' : '−'}${Math.abs(delta)} ${label}`,
        tone: delta > 0 ? 'gain' : field === 'health' ? 'damage' : 'cost', target: 'player' });
    }
  }
  if (after.strength > before.strength) {
    numbers.push({ text: 'Level up!', tone: 'gain', target: 'player' });
  } else if (after.experience > before.experience) {
    numbers.push({ text: `+${after.experience - before.experience} gathering XP`, tone: 'gain', target: 'player' });
  }
  if (before.status === 'COMBAT' && after.enemyHealth < before.enemyHealth && after.status !== 'DEAD') {
    // A retreat clears enemyHealth without damaging the animal; only confirmed attack exchanges count.
    const attacked = after.status === 'COMBAT' || after.message?.startsWith('You defeated ');
    if (attacked) numbers.push({ text: `−${before.enemyHealth - after.enemyHealth} enemy health`, tone: 'damage', target: 'enemy' });
  }
  const kind = after.health < before.health ? 'damage' : numbers.some(number => number.tone === 'gain') ? 'gain'
    : after.status === 'COMBAT' ? 'combat' : 'action';
  return { message: after.message || 'Action complete.', kind, numbers: numbers.slice(0, 6) };
}

/** Owns bounded DOM notices, frame-driven expiry and persistent combat HUD without additional timers. */
export function createWorldFeedback(documentRoot, now = () => Date.now()) {
  const notice = documentRoot.getElementById('surviveWorldNotice');
  const numberLayer = documentRoot.getElementById('surviveWorldNumbers');
  const combat = documentRoot.getElementById('surviveWorldCombat');
  const enemyMarker = documentRoot.getElementById('surviveWorldEnemyMarker');
  const shell = documentRoot.getElementById('surviveWorld');
  let previous = null;
  let expiresAt = 0;
  let particles = [];
  let pending = false;
  let visual = null;

  function reset() {
    notice.hidden = true;
    combat.hidden = true;
    enemyMarker.hidden = true;
    particles = [];
    numberLayer.replaceChildren();
    shell.dataset.feedback = '';
    visual = null;
    pending = false;
    expiresAt = 0;
  }
  function show(message, kind, numbers = []) {
    const startedAt = now();
    notice.textContent = message;
    notice.dataset.kind = kind;
    notice.hidden = false;
    shell.dataset.feedback = kind;
    expiresAt = startedAt + 6000;
    visual = { kind, startedAt };
    particles = numbers.map(number => {
      const element = documentRoot.createElement('span');
      element.textContent = number.text;
      element.className = `survive-world-number is-${number.tone}`;
      return { ...number, element };
    });
    numberLayer.replaceChildren(...particles.map(particle => particle.element));
  }
  return {
    beginAction(action) {
      pending = true;
      show(`${ACTION_LABELS[action] || 'Action'}…`, 'pending');
    },
    showError(message) {
      pending = false;
      show(message || 'The action could not be completed.', 'damage');
    },
    acceptState(next, busy = false) {
      if (previous?.survivorId !== next?.survivorId) reset();
      if (!next) { previous = null; return; }
      if (previous?.survivorId === next.survivorId && next.revision < previous.revision) return;
      const change = worldChangesBetween(previous, next);
      if (change) { pending = false; show(change.message, change.kind, change.numbers); }
      else if (pending && !busy) { pending = false; notice.hidden = true; }
      combat.hidden = next.status !== 'COMBAT';
      enemyMarker.hidden = combat.hidden;
      if (!combat.hidden) {
        const name = ENEMIES[next.enemy] || 'Wild animal';
        const label = `${name} · ${next.enemyHealth} health`;
        const enemyName = documentRoot.getElementById('surviveWorldEnemyName');
        if (enemyName.textContent !== label) enemyName.textContent = label;
        if (enemyMarker.textContent !== label) enemyMarker.textContent = label;
        const meter = documentRoot.getElementById('surviveWorldEnemyMeter');
        const maximum = ENEMY_HEALTH[next.enemy] || next.enemyHealth;
        if (meter.max !== maximum) meter.max = maximum;
        if (meter.value !== next.enemyHealth) meter.value = next.enemyHealth;
        const playerHealth = documentRoot.getElementById('surviveWorldPlayerHealth');
        const healthLabel = `Your health: ${next.health} / 10`;
        if (playerHealth.textContent !== healthLabel) playerHealth.textContent = healthLabel;
      }
      previous = { ...next };
    },
    draw(project, position, enemy) {
      if (!previous) return;
      const remaining = expiresAt - now();
      if (!pending && remaining <= 0) {
        notice.hidden = true;
        particles = [];
        numberLayer.replaceChildren();
        shell.dataset.feedback = '';
        visual = null;
      }
      const place = (element, point) => {
        const projected = project(point);
        element.hidden = !projected.visible;
        element.style.left = `${(projected.x + 1) * 50}%`;
        element.style.top = `${(1 - projected.y) * 50}%`;
      };
      if (previous.status === 'COMBAT') place(enemyMarker, [enemy.x, 3.4, enemy.z]);
      for (const [index, particle] of particles.entries()) {
        const anchor = particle.target === 'enemy' ? enemy : position;
        place(particle.element, [anchor.x, 2.9 + index * 0.55, anchor.z]);
      }
    },
    effect() { return !pending && visual && now() < expiresAt ? { ...visual, ageSeconds: (now() - visual.startedAt) / 1000 } : null; },
    dispose() { previous = null; reset(); },
  };
}
