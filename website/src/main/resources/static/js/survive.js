/** Display and transport only: the Java world owns all rules and allowed actions. */
import { API } from './lib/api.js';
import { fetchJson } from './lib/util.js';

const STATUS_LABELS = { EXPLORING: 'At camp', COMBAT: 'In combat', DEAD: 'Game over', ESCAPED: 'Survived' };
const SCENE_LABELS = {
  EXPLORING: 'At the edge of the wilderness', COMBAT: 'A hog blocks your path',
  DEAD: 'The wilderness claimed you', ESCAPED: 'A new horizon awaits',
};

/** Render server-supplied text as text nodes, including names and public journal entries. */
export function renderSurviveState(documentRoot, state, pending = false) {
  const setText = (id, text) => { documentRoot.getElementById(id).textContent = String(text); };
  documentRoot.getElementById('surviveGame').hidden = false;
  setText('survivePlayerName', state.name);
  setText('surviveStatus', STATUS_LABELS[state.status] || state.status);
  setText('surviveScene', SCENE_LABELS[state.status] || 'The wilderness');
  const message = documentRoot.getElementById('surviveMessage');
  if (message.textContent !== state.message) message.textContent = state.message;
  setText('surviveHealthText', `${state.health} / 10`);
  documentRoot.getElementById('surviveHealth').value = state.health;
  setText('surviveStrength', state.strength);
  setText('surviveStamina', state.stamina);
  setText('surviveExperience', `${state.experience} / ${state.experienceToNextLevel}`);
  setText('surviveWood', state.wood);
  setText('surviveFood', state.food);
  setText('surviveInventoryCount', `${state.wood + state.food} / ${state.inventoryCapacity}`);
  setText('surviveShelters', state.shelters);
  setText('surviveBoats', state.boats);
  setText('survivePlayerCount', state.survivors.length);
  documentRoot.getElementById('surviveEnemy').hidden = state.status !== 'COMBAT';
  setText('surviveEnemyHealth', `${state.enemyHealth} / 5 health`);
  for (const button of documentRoot.querySelectorAll('[data-action]')) {
    button.disabled = pending || !state.actions.includes(button.dataset.action);
    const isCombatAction = ['ATTACK', 'DEFEND', 'FLEE'].includes(button.dataset.action);
    button.hidden = isCombatAction !== (state.status === 'COMBAT');
  }
  for (const [id, entries] of [['survivePlayers', state.survivors], ['surviveEvents', [...state.events].reverse()]]) {
    const list = documentRoot.getElementById(id);
    const items = entries.map(text => {
      const item = documentRoot.createElement('li');
      item.textContent = text;
      return item;
    });
    list.replaceChildren(...items);
  }
}

/** Serialize browser requests; failed mutations are never automatically repeated. */
export function mountSurvive(documentRoot = document, requestJson = fetchJson) {
  const joinForm = documentRoot.getElementById('surviveJoin');
  if (!joinForm) return null;
  let state = null;
  let pending = false;
  const errorBox = documentRoot.getElementById('surviveError');
  const restart = documentRoot.getElementById('surviveRestart');
  const refresh = documentRoot.getElementById('surviveRefresh');
  const cancel = documentRoot.getElementById('surviveCancel');

  function setPending(isPending) {
    pending = isPending;
    for (const control of joinForm.querySelectorAll('input, button')) control.disabled = pending;
    restart.disabled = pending;
    refresh.disabled = pending;
    if (state) renderSurviveState(documentRoot, state, pending);
  }

  function acceptState(serverState) {
    state = serverState?.name ? serverState : null;
    documentRoot.getElementById('surviveGame').hidden = !state;
    joinForm.hidden = Boolean(state);
    cancel.hidden = !state;
    if (state) renderSurviveState(documentRoot, state, pending);
  }

  async function sendRequest(url, options = {}) {
    if (pending) return;
    setPending(true);
    errorBox.hidden = true;
    try {
      acceptState(await requestJson(url, { redirectOnUnauthorized: false, ...options }));
    } catch (error) {
      // Reconcile once with Java after any uncertain mutation outcome; never replay the command.
      if (options.method === 'POST') {
        try { acceptState(await requestJson(API.survive.game, { redirectOnUnauthorized: false })); }
        catch (_) { /* Keep the last confirmed view and show the original failure. */ }
      }
      errorBox.textContent = `${error.message || 'The server could not be reached.'} Refresh the world before trying another move.`;
      errorBox.hidden = false;
    } finally {
      setPending(false);
    }
  }

  const join = event => {
    event.preventDefault();
    void sendRequest(API.survive.game, {
      method: 'POST', body: JSON.stringify({ name: documentRoot.getElementById('surviveName').value.trim() }),
    });
  };
  const act = event => {
    const button = event.target.closest('[data-action]');
    if (!button || button.disabled || !state) return;
    void sendRequest(API.survive.actions, {
      method: 'POST', body: JSON.stringify({ action: button.dataset.action, revision: state.revision }),
    });
  };
  const showJoin = () => {
    joinForm.hidden = false;
    cancel.hidden = !state;
    documentRoot.getElementById('surviveJoinTitle').textContent = 'Replace your survivor';
    documentRoot.getElementById('surviveName').focus();
  };
  const cancelJoin = () => { joinForm.hidden = true; };
  const readWorld = () => { void sendRequest(API.survive.game); };
  joinForm.addEventListener('submit', join);
  documentRoot.getElementById('surviveActions').addEventListener('click', act);
  restart.addEventListener('click', showJoin);
  cancel.addEventListener('click', cancelJoin);
  refresh.addEventListener('click', readWorld);
  // Poll only when actively viewing a joined survivor; no background game mutation occurs.
  const timer = setInterval(() => {
    if (state && !pending && joinForm.hidden && !documentRoot.hidden) readWorld();
  }, 5000);
  readWorld();
  return {
    refresh: readWorld,
    dispose() {
      clearInterval(timer);
      joinForm.removeEventListener('submit', join);
      documentRoot.getElementById('surviveActions').removeEventListener('click', act);
      restart.removeEventListener('click', showJoin);
      cancel.removeEventListener('click', cancelJoin);
      refresh.removeEventListener('click', readWorld);
    },
  };
}

/** Keep controls attached while the browser parks this document in its back/forward cache. */
export function disposeSurviveOnPageHide(mountedGame, event) {
  if (!event.persisted) mountedGame?.dispose();
}

if (typeof document !== 'undefined') {
  const mountedGame = mountSurvive();
  window.addEventListener('pagehide', event => disposeSurviveOnPageHide(mountedGame, event));
}
