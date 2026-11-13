/** Display and transport only: the Java world owns all rules and allowed actions. */
import { API } from './lib/api.js';
import { fetchJson } from './lib/util.js';
import { appendSurviveLogMessage, syncSurviveLog } from './survive-log.js';
import { mountSurviveController } from './survive-controller.js';
import { attackDelayLabel, renderSurviveItems } from './survive-items.js';
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
  const attention = state.enemyAttention;
  documentRoot.getElementById('surviveHudEnemyAttention').hidden = !attention;
  setText('surviveHudEnemyAttention', attention ? "Enemy attention · " + (attention.self ? 'You' : attention.name) : '');
  setText('surviveHudTp', `TP ${state.tacticalPoints ?? 0}`);
  const partnerVitals = state.party?.state === 'MEMBER' ? state.party.vitals : null;
  documentRoot.getElementById('surviveHudPartner').hidden = !partnerVitals;
  setText('surviveHudPartnerName', partnerVitals ? state.party.name : '');
  setText('surviveHudPartnerHealth', partnerVitals ? `HP ${partnerVitals.health}/${partnerVitals.maxHealth}` : '');
  setText('surviveHudPartnerTp', partnerVitals ? `TP ${partnerVitals.tacticalPoints}` : '');
  documentRoot.getElementById('surviveHealth').value = state.health;
  setText('surviveStrength', state.strength);
  setText('surviveStamina', state.stamina);
  setText('surviveExperience', `${state.experience} / ${state.experienceToNextLevel}`);
  setText('surviveHungerText', `${state.hunger} / 10`);
  documentRoot.getElementById('surviveHunger').value = state.hunger;
  documentRoot.getElementById('surviveHungerWarning').hidden = state.hunger > HUNGER_WARNING_LEVEL;
  const packedWeapons = state.packedWeapons ?? [];
  const carried = state.wood + state.food + state.stone + packedWeapons.length;
  setText('surviveInventoryCount', `${carried} / ${state.inventoryCapacity}`);
  renderSurviveItems(documentRoot, state, mutationPending);
  setText('surviveWeapon', state.weapon
    ? `${WEAPON_LABELS[state.weapon] || state.weapon}${attackDelayLabel(state.attackDelayMilliseconds)} · ${state.weaponDurability} uses left`
    : `Bare hands${attackDelayLabel(state.attackDelayMilliseconds)}`);
  renderWeaponChoices(documentRoot, packedWeapons, state.actions, mutationPending);
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
  renderRecipientOptions(documentRoot, recipientPicker, state.recipients);
  const unavailable = !state.recipients.length || carried === 0;
  for (const id of ['surviveRecipient', 'surviveGiftResource', 'surviveGiftQuantity']) {
    documentRoot.getElementById(id).disabled = mutationPending || unavailable;
  }
  documentRoot.getElementById('surviveGive').disabled = pending || unavailable || !recipientPicker.value;
  setText('surviveGiftHint', !state.recipients.length
    ? 'You and another survivor must be at camp to share supplies.'
    : 'Give supplies to help another survivor. Their inventory must have room.');
  renderTrade(documentRoot, state, pending);
}

function renderRecipientOptions(documentRoot, recipientPicker, recipients) {
  const previousRecipient = recipientPicker.value;
  const recipientSignature = JSON.stringify(recipients);
  // Preserve the native chooser and input focus when a poll has no recipient changes.
  if (recipientPicker.dataset.recipientSignature !== recipientSignature) {
    const chooseRecipient = documentRoot.createElement('option');
    chooseRecipient.value = '';
    chooseRecipient.textContent = 'Choose a survivor';
    recipientPicker.replaceChildren(chooseRecipient, ...recipients.map(recipient => {
      const option = documentRoot.createElement('option');
      option.value = recipient.survivorId;
      option.textContent = `${recipient.name} · ${recipient.survivorId.slice(0, 8)}`;
      return option;
    }));
    recipientPicker.dataset.recipientSignature = recipientSignature;
  }
  recipientPicker.value = recipients.some(recipient => recipient.survivorId === previousRecipient)
    ? previousRecipient : '';
}

function renderTrade(documentRoot, state, pending) {
  const picker = documentRoot.getElementById('surviveTradePartner');
  if (!picker) return;
  const offer = state.trade;
  const offerForm = documentRoot.getElementById('surviveTradeOffer');
  offerForm.hidden = Boolean(offer);
  renderRecipientOptions(documentRoot, picker, state.recipients);
  documentRoot.getElementById('surviveTradeSend').disabled = pending || state.recipients.length === 0;
  documentRoot.getElementById('surviveTradePending').hidden = !offer;
  if (!offer) return;
  const { offered, requested } = offer.offer;
  const give = offer.incoming ? requested : offered;
  const receive = offer.incoming ? offered : requested;
  documentRoot.getElementById('surviveTradeTerms').textContent =
    `Trade with ${offer.partner}. Give ${give.quantity} ${give.resource.toLowerCase()} and receive ${receive.quantity} ${receive.resource.toLowerCase()}.`;
  const accept = documentRoot.getElementById('surviveTradeAccept');
  accept.hidden = !offer.incoming;
  accept.disabled = pending;
  const end = documentRoot.getElementById('surviveTradeEnd');
  end.textContent = offer.incoming ? 'Decline offer' : 'Withdraw offer';
  end.disabled = pending;
}

function renderWeaponChoices(documentRoot, weapons, actions, pending) {
  const choices = documentRoot.getElementById('surviveWeaponChoices');
  if (!choices) return;
  const signature = weapons.map(weapon => weapon.id).join(',');
  if (choices.dataset.weapons !== signature) {
    const focused = choices.contains(documentRoot.activeElement);
    const rows = weapons.map(weapon => {
      const row = documentRoot.createElement('p');
      const button = documentRoot.createElement('button');
      button.type = 'button';
      button.dataset.equipmentAction = 'EQUIP_WEAPON';
      button.dataset.itemId = weapon.id;
      button.textContent = `Equip ${weapon.name} · +${weapon.damageBonus} damage${attackDelayLabel(weapon.attackDelayMilliseconds)} · ${weapon.durability} uses left`;
      row.append(button);
      return row;
    });
    choices.replaceChildren(...rows);
    choices.dataset.weapons = signature;
    if (focused) choices.parentElement.querySelector('summary').focus({ preventScroll: true });
  }
  for (const button of choices.querySelectorAll('button')) {
    button.disabled = !actions.includes('EQUIP_WEAPON');
    button.setAttribute('aria-disabled', String(pending));
  }
  const stow = documentRoot.getElementById('surviveStowWeapon');
  stow.hidden = !actions.includes('STOW_WEAPON');
  stow.setAttribute('aria-disabled', String(pending));
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
  const equipment = documentRoot.getElementById('surviveEquipment');
  const itemList = documentRoot.getElementById('surviveItemList');
  const itemSort = documentRoot.getElementById('surviveItemSort');
  const itemUse = documentRoot.getElementById('surviveItemUse');
  const itemDrop = documentRoot.getElementById('surviveItemDrop');
  const dropConfirm = documentRoot.getElementById('surviveDropConfirm');
  const dropPrompt = documentRoot.getElementById('surviveDropPrompt');
  const confirmDrop = documentRoot.getElementById('surviveConfirmDrop');
  const cancelDrop = documentRoot.getElementById('surviveCancelDrop');
  const expandPack = documentRoot.getElementById('surviveExpandPack');
  let pendingDrop;
  const playShell = documentRoot.getElementById('survivePlayShell');
  const gameViewToggle = documentRoot.getElementById('surviveGameViewToggle');
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
    const previous = state;
    state = serverState?.name ? serverState : null;
    documentRoot.getElementById('surviveGame').hidden = !state;
    joinForm.hidden = Boolean(state);
    cancel.hidden = !state;
    if (state) {
      phaseEndsAtMilliseconds = Date.now() + state.secondsToNextPhase * 1000;
      const delay = state.nextStrikeAt ? Math.max(100, (state.millisecondsUntilStrike ?? 0) + 50)
        : state.fishing ? Math.min(5000, state.fishing.millisecondsUntilEscape + 50) : 5000;
      nextWorldPollAt = Date.now() + delay;
      renderSurviveState(documentRoot, state, pending, mutationPending);
      if (previous?.survivorId === state.survivorId) {
        if (state.trade && state.trade.offer.id !== previous.trade?.offer.id) {
          const { offered, requested } = state.trade.offer;
          const terms = `${offered.quantity} ${offered.resource.toLowerCase()} for ${requested.quantity} ${requested.resource.toLowerCase()}`;
          appendSurviveLogMessage(documentRoot, state.trade.incoming
            ? `${state.trade.partner} offered ${terms}. Open Trade in the camp menu to respond.`
            : `You offered ${terms} to ${state.trade.partner}.`);
        } else if (previous.trade && !state.trade) {
          const tradeResult = state.message?.startsWith('You traded ') || state.message?.endsWith(' ended the trade offer.');
          appendSurviveLogMessage(documentRoot, tradeResult ? state.message : 'The trade offer is no longer available.');
        }
      }
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
    const fieldTalk = action === 'TALK_WAYSTATION' && state?.status === 'EXPLORING';
    if (!state || pending || !fieldEngage && !fieldTalk && !state.actions.includes(action)) return;
    void sendRequest(API.survive.actions, {
      method: 'POST', body: JSON.stringify({ action, revision: state.revision, targetId }),
    }, action);
  }
  const useEquipment = event => {
    const button = event.target.closest('[data-equipment-action]');
    if (button && !button.disabled && button.getAttribute('aria-disabled') !== 'true') {
      performAction(button.dataset.equipmentAction, button.dataset.itemId);
    }
  };
  equipment?.addEventListener('click', useEquipment);
  const updateItems = () => {
    if (state) renderSurviveItems(documentRoot, state, mutationPending);
  };
  const sortItems = () => {
    itemList.dataset.sort = itemList.dataset.sort === 'name' ? 'type' : 'name';
    updateItems();
  };
  const useItem = () => {
    if (itemList.value === 'food' && !itemUse.disabled && !mutationPending) performAction('EAT');
  };
  itemList?.addEventListener('change', updateItems);
  itemSort?.addEventListener('click', sortItems);
  function closeDrop() {
    pendingDrop = undefined;
    dropConfirm.hidden = true;
    itemList.focus({ preventScroll: true });
  }

  function openDrop() {
    if (mutationPending || !state?.actions.includes('DROP_ITEM') || !itemList.value) return;
    pendingDrop = { id: itemList.value, survivorId: state.survivorId };
    dropPrompt.textContent = 'Permanently discard one ' + itemList.selectedOptions[0].textContent.split(' ×')[0] + '?';
    dropConfirm.hidden = false;
    cancelDrop.focus({ preventScroll: true });
  }

  function dropItem() {
    const selected = pendingDrop;
    closeDrop();
    if (selected && selected.survivorId === state?.survivorId && selected.id === itemList.value
        && state?.actions.includes('DROP_ITEM') && !mutationPending) performAction('DROP_ITEM', selected.id);
  }

  function upgradePack() {
    if (!mutationPending && state?.actions.includes('EXPAND_PACK')) performAction('EXPAND_PACK');
  }

  itemDrop?.addEventListener('click', openDrop);
  confirmDrop?.addEventListener('click', dropItem);
  cancelDrop?.addEventListener('click', closeDrop);
  expandPack?.addEventListener('click', upgradePack);
  itemUse?.addEventListener('click', useItem);
  const toggleGameView = () => {
    const showPage = playShell.dataset.windowed !== 'true';
    playShell.dataset.windowed = String(showPage);
    gameViewToggle.textContent = showPage ? 'Fill browser window' : 'Show page view';
  };
  gameViewToggle?.addEventListener('click', toggleGameView);
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
  const tradePanel = documentRoot.getElementById('surviveTrade');
  const tradeOfferForm = documentRoot.getElementById('surviveTradeOffer');
  const sendTrade = event => {
    event.preventDefault();
    if (!state || pending || state.trade) return;
    const fields = new FormData(tradeOfferForm);
    void sendRequest(API.survive.trades, {
      method: 'POST', body: JSON.stringify({
        recipientId: fields.get('recipientId'),
        offered: fields.get('offered'),
        offeredQuantity: Number(fields.get('offeredQuantity')),
        requested: fields.get('requested'),
        requestedQuantity: Number(fields.get('requestedQuantity')),
        revision: state.revision,
      }),
    });
  };
  const replyToTrade = event => {
    const reply = event.target.closest('[data-trade-reply]');
    if (!reply || !state?.trade || pending) return;
    void sendRequest(API.survive.tradeReply, {
      method: 'POST', body: JSON.stringify({
        offerId: state.trade.offer.id,
        accept: reply.dataset.tradeReply === 'accept',
        revision: state.revision,
      }),
    });
  };
  tradeOfferForm?.addEventListener('submit', sendTrade);
  tradePanel?.addEventListener('click', replyToTrade);
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
      equipment?.removeEventListener('click', useEquipment);
      itemList?.removeEventListener('change', updateItems);
      itemSort?.removeEventListener('click', sortItems);
      itemDrop?.removeEventListener('click', openDrop);
      confirmDrop?.removeEventListener('click', dropItem);
      cancelDrop?.removeEventListener('click', closeDrop);
      expandPack?.removeEventListener('click', upgradePack);
      itemUse?.removeEventListener('click', useItem);
      gameViewToggle?.removeEventListener('click', toggleGameView);
      controller.dispose();
      world.dispose();
      documentRoot.getElementById('surviveCampScene').removeEventListener('click', act);
      restart.removeEventListener('click', showJoin);
      cancel.removeEventListener('click', cancelJoin);
      refresh.removeEventListener('click', readWorld);
      logToggle.removeEventListener('click', toggleLog);
      game.removeEventListener('keydown', useLogShortcut);
      tradeOfferForm?.removeEventListener('submit', sendTrade);
      tradePanel?.removeEventListener('click', replyToTrade);
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
