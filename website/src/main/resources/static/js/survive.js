/** Display and transport only: the Java world owns all rules and allowed actions. */
import { API } from './lib/api.js';
import { fetchJson } from './lib/util.js';

const STATUS_LABELS = { EXPLORING: 'At camp', COMBAT: 'In combat', DEAD: 'Game over', ESCAPED: 'Survived' };
const SCENE_LABELS = {
  EXPLORING: 'At the edge of the wilderness', COMBAT: 'A hog blocks your path',
  DEAD: 'The wilderness claimed you', ESCAPED: 'A new horizon awaits',
};

/** Render server-supplied text as text nodes, including names and public journal entries. */
export function renderSurviveState(documentRoot, state, pending = false, mutationPending = pending) {
  const setText = (id, text) => { documentRoot.getElementById(id).textContent = String(text); };
  documentRoot.getElementById('surviveGame').hidden = false;
  setText('survivePlayerName', state.name);
  setText('surviveSaveStatus', state.saved
    ? 'Saved to your account - Resume when you log in again.'
    : 'Temporary guest - Log in for a separate saved survivor.');
  documentRoot.getElementById('surviveRestart').disabled = pending
    || Boolean(state.saved && !['DEAD', 'ESCAPED'].includes(state.status));
  setText('survivePublicId', `Camp ID: ${state.survivorId.slice(0, 8)}`);
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
  const recipientPicker = documentRoot.getElementById('surviveRecipient');
  const previousRecipient = recipientPicker.value;
  const recipientSignature = JSON.stringify(state.recipients);
  // Preserve the native chooser and input focus when a poll has no recipient changes.
  if (recipientPicker.dataset.recipientSignature !== recipientSignature) {
    const chooseRecipient = documentRoot.createElement('option');
    chooseRecipient.value = '';
    chooseRecipient.textContent = 'Choose a survivor';
    recipientPicker.replaceChildren(chooseRecipient, ...state.recipients.map(recipient => {
      const option = documentRoot.createElement('option');
      option.value = recipient.survivorId;
      option.textContent = `${recipient.name} · ${recipient.survivorId.slice(0, 8)}`;
      return option;
    }));
    recipientPicker.dataset.recipientSignature = recipientSignature;
  }
  recipientPicker.value = state.recipients.some(recipient => recipient.survivorId === previousRecipient)
    ? previousRecipient : '';
  const unavailable = !state.recipients.length || state.wood + state.food === 0;
  for (const id of ['surviveRecipient', 'surviveGiftResource', 'surviveGiftQuantity']) {
    documentRoot.getElementById(id).disabled = mutationPending || unavailable;
  }
  documentRoot.getElementById('surviveGive').disabled = pending || unavailable || !recipientPicker.value;
  setText('surviveGiftHint', !state.recipients.length
    ? 'You and another survivor must be at camp to share supplies.'
    : 'Give supplies to help another survivor. Their inventory must have room.');
}

/** Serialize browser requests; failed mutations are never automatically repeated. */
export function mountSurvive(documentRoot = document, requestJson = fetchJson) {
  const joinForm = documentRoot.getElementById('surviveJoin');
  if (!joinForm) return null;
  let state = null;
  let pending = false;
  let mutationPending = false;
  const errorBox = documentRoot.getElementById('surviveError');
  const restart = documentRoot.getElementById('surviveRestart');
  const refresh = documentRoot.getElementById('surviveRefresh');
  const cancel = documentRoot.getElementById('surviveCancel');
  const giftForm = documentRoot.getElementById('surviveGift');

  function setPending(isPending, isMutation = false) {
    pending = isPending;
    mutationPending = isPending && isMutation;
    for (const control of joinForm.querySelectorAll('input, button')) control.disabled = pending;
    restart.disabled = pending;
    refresh.disabled = pending;
    if (state) renderSurviveState(documentRoot, state, pending, mutationPending);
  }

  function acceptState(serverState) {
    state = serverState?.name ? serverState : null;
    documentRoot.getElementById('surviveGame').hidden = !state;
    joinForm.hidden = Boolean(state);
    cancel.hidden = !state;
    if (state) renderSurviveState(documentRoot, state, pending, mutationPending);
  }

  async function sendRequest(url, options = {}) {
    if (pending) return;
    setPending(true, options.method === 'POST');
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
  const chooseRecipient = () => {
    if (state) renderSurviveState(documentRoot, state, pending, mutationPending);
  };
  const give = event => {
    event.preventDefault();
    if (!state || pending || documentRoot.getElementById('surviveGive').disabled) return;
    void sendRequest(API.survive.gifts, {
      method: 'POST', body: JSON.stringify({
        recipientId: documentRoot.getElementById('surviveRecipient').value,
        resource: documentRoot.getElementById('surviveGiftResource').value,
        quantity: Number(documentRoot.getElementById('surviveGiftQuantity').value),
        revision: state.revision,
      }),
    });
  };
  giftForm.addEventListener('submit', give);
  documentRoot.getElementById('surviveRecipient').addEventListener('change', chooseRecipient);
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
      giftForm.removeEventListener('submit', give);
      documentRoot.getElementById('surviveRecipient').removeEventListener('change', chooseRecipient);
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
