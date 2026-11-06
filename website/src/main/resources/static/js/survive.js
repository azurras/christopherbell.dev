/** Display and transport only: the Java world owns all rules and allowed actions. */
import { API } from './lib/api.js';
import { fetchJson } from './lib/util.js';
import { appendSurviveLogMessage, syncSurviveLog } from './survive-log.js';
import { mountSurviveController } from './survive-controller.js';
import { keepFocusInActionBar, mountActionMenus, syncActionMenus } from './survive-menus.js';
import { mountPanelSections, summarizeCamp } from './survive-panel.js';
import { mountSurviveParty } from './survive-party.js';
import { renderSurviveScene } from './survive-scene.js';
import { mountSurviveWorld } from './survive-world.js';

const STATUS_LABELS = {
  EXPLORING: 'Exploring', COMBAT: 'In combat', SLEEPING: 'Asleep', DEAD: 'Game over', ESCAPED: 'Survived',
};
const SCENE_LABELS = {
  EXPLORING: 'At the edge of the wilderness', SLEEPING: 'Asleep in a shelter bed',
  DEAD: 'The wilderness claimed you', ESCAPED: 'A new horizon awaits',
};
const COMBAT_SCENES = { HOG: 'A wild hog blocks your path', WOLF: 'A wolf circles you', BEAR: 'A bear rears up before you',
  RAIDER: 'A raider attacks the camp' };
const ENEMY_LABELS = { HOG: 'Wild hog', WOLF: 'Grey wolf', BEAR: 'Brown bear', RAIDER: 'Camp raider' };
const WEAPON_LABELS = { CLUB: 'Club', STONE_AXE: 'Stone axe', SPEAR: 'Spear' };
const WORLD_DAWN_EPOCH = Date.UTC(1000, 0, 1, 6);
const PHASE_END_MINUTES = { DAY: 840, DUSK: 1080, NIGHT: 1440 };
const HUNGER_WARNING_LEVEL = 3;

/** The server's 24 real minutes per cycle equal 24 hours on the island calendar. */
export function formatWorldCalendar(day, phase, secondsToNextPhase) {
  const minutes = PHASE_END_MINUTES[phase] - Math.max(0, Math.ceil(secondsToNextPhase));
  const time = new Date(WORLD_DAWN_EPOCH + ((day - 1) * 1440 + minutes) * 60000);
  const iso = time.toISOString();
  return { label: `${iso.slice(5, 7)}/${iso.slice(8, 10)}/${iso.slice(0, 4)} · ${iso.slice(11, 16)}`, iso };
}

function renderWorldCalendar(documentRoot, state, secondsLeft) {
  const display = documentRoot.getElementById('surviveCalendar');
  const date = formatWorldCalendar(state.day, state.phase, secondsLeft);
  display.textContent = date.label;
  display.setAttribute('datetime', date.iso);
}

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
  const isGuarding = state.status === 'EXPLORING' && state.guarding;
  setText('surviveStatus', isGuarding ? 'On guard' : STATUS_LABELS[state.status] || state.status);
  setText('surviveScene', state.status === 'COMBAT'
    ? COMBAT_SCENES[state.enemy] || 'Something blocks your path'
    : SCENE_LABELS[state.status] || 'The wilderness');
  renderSurviveScene(documentRoot, documentRoot.getElementById('surviveCampScene'), state, pending);
  documentRoot.getElementById('surviveClock').dataset.phase = state.phase;
  renderWorldCalendar(documentRoot, state, state.secondsToNextPhase);
  setText('surviveHealthText', `${state.health} / 10`);
  setText('surviveHudName', state.name);
  setText('surviveHudHealth', `HP ${state.health}/10`);
  setText('surviveHudTp', `TP ${state.tacticalPoints ?? 0}`);
  documentRoot.getElementById('surviveHealth').value = state.health;
  setText('surviveStrength', state.strength);
  setText('surviveStamina', state.stamina);
  setText('surviveExperience', `${state.experience} / ${state.experienceToNextLevel}`);
  setText('surviveHungerText', `${state.hunger} / 10`);
  documentRoot.getElementById('surviveHunger').value = state.hunger;
  documentRoot.getElementById('surviveHungerWarning').hidden = state.hunger > HUNGER_WARNING_LEVEL;
  setText('surviveWood', state.wood);
  setText('surviveFood', state.food);
  setText('surviveStone', state.stone);
  const carried = state.wood + state.food + state.stone;
  setText('surviveInventoryCount', `${carried} / ${state.inventoryCapacity}`);
  setText('surviveWeapon', state.weapon
    ? `${WEAPON_LABELS[state.weapon] || state.weapon} · ${state.weaponDurability} uses left`
    : 'Bare hands');
  setText('surviveShelters', state.shelters);
  setText('surviveBeds', `${state.bedsInUse} / ${state.beds}`);
  setText('surviveBoats', state.boats);
  setText('survivePlayerCount', state.survivors.length);
  documentRoot.getElementById('surviveEnemy').hidden = state.status !== 'COMBAT';
  setText('surviveEnemyName', ENEMY_LABELS[state.enemy] || 'Wild animal');
  setText('surviveEnemyHealth', `${state.enemyHealth} health · ${state.tacticalPoints ?? 0} TP`);
  // Menus and the direct group list only moves the server allows now; menus appear while exploring.
  const actionBar = documentRoot.getElementById('surviveActionBar');
  const focusWasInActionBar = actionBar.contains(documentRoot.activeElement);
  for (const move of actionBar.querySelectorAll('[data-action]')) {
    const isAllowed = state.actions.includes(move.dataset.action);
    move.hidden = !isAllowed;
    move.disabled = !isAllowed;
  }
  // Busy rather than disabled: disabling the focused move during a poll would drop keyboard focus.
  // The click handler already ignores every move while a request is in flight.
  actionBar.setAttribute('aria-busy', String(pending));
  documentRoot.getElementById('surviveMenus').hidden = state.status !== 'EXPLORING';
  const startOver = documentRoot.getElementById('surviveStartOver');
  startOver.hidden = !['DEAD', 'ESCAPED'].includes(state.status);
  startOver.disabled = pending;
  syncActionMenus(documentRoot, actionBar);
  if (focusWasInActionBar) {
    keepFocusInActionBar(documentRoot, actionBar);
  }
  setText('surviveCampSummary', summarizeCamp(state));
  const players = state.survivors.map(name => {
    const item = documentRoot.createElement('li');
    item.textContent = name;
    return item;
  });
  documentRoot.getElementById('survivePlayers').replaceChildren(...players);
  syncSurviveLog(documentRoot, state);
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
  const unavailable = !state.recipients.length || carried === 0;
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
  if (!joinForm) {
    return null;
  }
  let state = null;
  let pending = false;
  let mutationPending = false;
  let phaseEndsAtMilliseconds = 0;
  let nextWorldPollAt = 0;
  const errorBox = documentRoot.getElementById('surviveError');
  const restart = documentRoot.getElementById('surviveRestart');
  const startOver = documentRoot.getElementById('surviveStartOver');
  const actionBar = documentRoot.getElementById('surviveActionBar');
  const menus = mountActionMenus(documentRoot, actionBar);
  const panel = mountPanelSections(documentRoot);
  const refresh = documentRoot.getElementById('surviveRefresh');
  const cancel = documentRoot.getElementById('surviveCancel');
  const giftForm = documentRoot.getElementById('surviveGift');
  const game = documentRoot.getElementById('surviveGame');
  const log = documentRoot.getElementById('surviveGameLog');
  const logToggle = documentRoot.getElementById('surviveLogToggle');
  const logScrollback = documentRoot.getElementById('surviveLogScrollback');
  const world = mountSurviveWorld(documentRoot, requestJson, performAction, undefined, () => readWorld());
  const controller = mountSurviveController(documentRoot, world, () => { if (state) readWorld(); });
  const party = mountSurviveParty(documentRoot, (command, targetId) => {
    void sendRequest(API.survive.party, { method: 'POST', body: JSON.stringify({ command, targetId }) });
  });

  function setLogExpanded(expanded) {
    const followsLatest = logScrollback.scrollHeight - logScrollback.clientHeight - logScrollback.scrollTop <= 4;
    logToggle.setAttribute('aria-expanded', String(expanded));
    logToggle.textContent = expanded ? 'Collapse' : 'Expand';
    log.dataset.expanded = String(expanded);
    if (followsLatest) logScrollback.scrollTop = logScrollback.scrollHeight;
    (expanded ? logScrollback : logToggle).focus({ preventScroll: true });
  }

  const toggleLog = () => setLogExpanded(log.dataset.expanded !== 'true');
  const useLogShortcut = event => {
    if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey
        || event.target.closest('input, select, textarea')) return;
    if (event.key === '+' && !event.repeat) {
      event.preventDefault();
      toggleLog();
    } else if (event.key === 'Escape' && log.dataset.expanded === 'true' && log.contains(event.target)) {
      event.preventDefault();
      setLogExpanded(false);
    }
  };

  function setPending(isPending, isMutation = false) {
    pending = isPending;
    mutationPending = isPending && isMutation;
    for (const control of joinForm.querySelectorAll('input, button')) {
      control.disabled = pending;
    }
    restart.disabled = pending;
    refresh.disabled = pending;
    if (state) {
      renderSurviveState(documentRoot, state, pending, mutationPending);
    }
    party?.render(state, mutationPending);
    world.setState(state, mutationPending);
  }

  function acceptState(serverState) {
    state = serverState?.name ? serverState : null;
    documentRoot.getElementById('surviveGame').hidden = !state;
    joinForm.hidden = Boolean(state);
    cancel.hidden = !state;
    if (state) {
      phaseEndsAtMilliseconds = Date.now() + state.secondsToNextPhase * 1000;
      const delay = state.nextStrikeAt ? Math.max(100, (state.millisecondsUntilStrike ?? 0) + 50) : 5000;
      nextWorldPollAt = Date.now() + delay;
      renderSurviveState(documentRoot, state, pending, mutationPending);
    }
    party?.render(state, mutationPending);
    world.setState(state, mutationPending);
  }

  async function sendRequest(url, options = {}, action) {
    if (pending || (game.inert && options.method === 'POST')) {
      return;
    }
    setPending(true, options.method === 'POST');
    errorBox.hidden = true;
    try {
      acceptState(await requestJson(url, { redirectOnUnauthorized: false, ...options }));
      if (action) world.animateAction?.(action);
    } catch (error) {
      // Reconcile once with Java after any uncertain mutation outcome; never replay the command.
      if (options.method === 'POST') {
        try { acceptState(await requestJson(API.survive.game, { redirectOnUnauthorized: false })); }
        catch (_) { /* Keep the last confirmed view and show the original failure. */ }
      }
      const message = `${error.message || 'The server could not be reached.'} Refresh the world before trying another move.`;
      if (state && joinForm.hidden) {
        appendSurviveLogMessage(documentRoot, message);
      } else {
        errorBox.textContent = message;
        errorBox.hidden = false;
      }
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
  // Buttons and scene hotspots share one path: only an action the latest snapshot allows, one at a time.
  const act = event => {
    const control = event.target.closest('[data-action]');
    if (!control || control.disabled || !state || pending) {
      return;
    }
    performAction(control.dataset.action);
  };
  function performAction(action, targetId) {
    const fieldEngage = action === 'ENGAGE' && targetId && state?.status === 'EXPLORING';
    if (!state || pending || !fieldEngage && !state.actions.includes(action)) return;
    void sendRequest(API.survive.actions, {
      method: 'POST', body: JSON.stringify({ action, revision: state.revision, targetId }),
    }, action);
  }
  const showJoin = () => {
    joinForm.hidden = false;
    cancel.hidden = !state;
    documentRoot.getElementById('surviveJoinTitle').textContent = 'Replace your survivor';
    documentRoot.getElementById('surviveName').focus();
  };
  const cancelJoin = () => { joinForm.hidden = true; };
  const readWorld = () => { void sendRequest(API.survive.game); };
  const chooseRecipient = () => {
    if (state) {
      renderSurviveState(documentRoot, state, pending, mutationPending);
    }
  };
  const give = event => {
    event.preventDefault();
    if (!state || pending || documentRoot.getElementById('surviveGive').disabled) {
      return;
    }
    void sendRequest(API.survive.gifts, {
      method: 'POST', body: JSON.stringify({
        recipientId: documentRoot.getElementById('surviveRecipient').value,
        resource: documentRoot.getElementById('surviveGiftResource').value,
        quantity: Number(documentRoot.getElementById('surviveGiftQuantity').value),
        revision: state.revision,
      }),
    }, 'GIVE');
  };
  giftForm.addEventListener('submit', give);
  documentRoot.getElementById('surviveRecipient').addEventListener('change', chooseRecipient);
  joinForm.addEventListener('submit', join);
  actionBar.addEventListener('click', act);
  startOver.addEventListener('click', showJoin);
  documentRoot.getElementById('surviveCampScene').addEventListener('click', act);
  restart.addEventListener('click', showJoin);
  cancel.addEventListener('click', cancelJoin);
  refresh.addEventListener('click', readWorld);
  logToggle.addEventListener('click', toggleLog);
  game.addEventListener('keydown', useLogShortcut);
  const isWatchingWorld = () => state && !pending && !game.inert && joinForm.hidden && !documentRoot.hidden;
  // Refresh shortly after a confirmed deadline while fighting. The server owns elapsed rounds.
  const timer = setInterval(() => {
    if (isWatchingWorld() && Date.now() >= nextWorldPollAt) {
      nextWorldPollAt = Date.now() + 5000;
      readWorld();
    }
  }, 250);
  const refreshOnReturn = () => { if (isWatchingWorld()) readWorld(); };
  documentRoot.addEventListener?.('visibilitychange', refreshOnReturn);
  // Count down locally between polls and read the world as soon as the phase changes.
  const clockTimer = setInterval(() => {
    if (!state) {
      return;
    }
    const secondsLeft = Math.max(0, Math.ceil((phaseEndsAtMilliseconds - Date.now()) / 1000));
    renderWorldCalendar(documentRoot, state, secondsLeft);
    if (secondsLeft === 0 && isWatchingWorld()) {
      readWorld();
    }
  }, 1000);
  readWorld();
  return {
    refresh: readWorld,
    dispose() {
      clearInterval(timer);
      clearInterval(clockTimer);
      documentRoot.removeEventListener?.('visibilitychange', refreshOnReturn);
      joinForm.removeEventListener('submit', join);
      actionBar.removeEventListener('click', act);
      startOver.removeEventListener('click', showJoin);
      menus.dispose();
      panel.dispose();
      party?.dispose();
      controller.dispose();
      world.dispose();
      documentRoot.getElementById('surviveCampScene').removeEventListener('click', act);
      restart.removeEventListener('click', showJoin);
      cancel.removeEventListener('click', cancelJoin);
      refresh.removeEventListener('click', readWorld);
      logToggle.removeEventListener('click', toggleLog);
      game.removeEventListener('keydown', useLogShortcut);
      giftForm.removeEventListener('submit', give);
      documentRoot.getElementById('surviveRecipient').removeEventListener('change', chooseRecipient);
    },
  };
}

/** Keep controls attached while the browser parks this document in its back/forward cache. */
export function disposeSurviveOnPageHide(mountedGame, event) {
  if (!event.persisted) {
    mountedGame?.dispose();
  }
}

if (typeof document !== 'undefined') {
  const mountedGame = mountSurvive();
  window.addEventListener('pagehide', event => disposeSurviveOnPageHide(mountedGame, event));
}
