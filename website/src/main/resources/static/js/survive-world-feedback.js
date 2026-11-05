/** The target HUD reflects current combat state. Command results belong in the chat log. */
const ENEMIES = { HOG: 'Wild hog', WOLF: 'Grey wolf', BEAR: 'Brown bear' };
const ENEMY_HEALTH = { HOG: 5, WOLF: 8, BEAR: 14 };

export function createWorldFeedback(documentRoot) {
  const combat = documentRoot.getElementById('surviveWorldCombat');
  const marker = documentRoot.getElementById('surviveWorldEnemyMarker');
  const title = documentRoot.getElementById('surviveWorldCombatTitle');
  const enemyName = documentRoot.getElementById('surviveWorldEnemyName');
  const meter = documentRoot.getElementById('surviveWorldEnemyMeter');

  function hideTarget() {
    combat.hidden = true;
    marker.hidden = true;
  }

  return {
    acceptState(state) {
      if (state?.status !== 'COMBAT') {
        hideTarget();
        return;
      }
      combat.hidden = false;
      marker.hidden = false;
      const heading = state.nextStrikeAt ? 'In combat' : 'Animal nearby';
      const label = `${ENEMIES[state.enemy] || 'Wild animal'} · ${state.enemyHealth} health`;
      if (title.textContent !== heading) title.textContent = heading;
      if (enemyName.textContent !== label) enemyName.textContent = label;
      if (marker.textContent !== label) marker.textContent = label;
      meter.max = ENEMY_HEALTH[state.enemy] || state.enemyHealth;
      meter.value = state.enemyHealth;
    },
    draw(project, enemy) {
      if (combat.hidden) return;
      const projected = project([enemy.x, 3.4, enemy.z]);
      marker.hidden = !projected.visible;
      marker.style.left = `${(projected.x + 1) * 50}%`;
      marker.style.top = `${(1 - projected.y) * 50}%`;
    },
    dispose: hideTarget,
  };
}
