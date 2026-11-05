/** Owns 3D input, prediction, acknowledged live presence, labels and cleanup for one page. */
import { API } from './lib/api.js';
import { appendSurviveLogMessage } from './survive-log.js';
import { advancePosition, actionsAt, ACTION_LABELS, LANDMARKS, combatPosition, targetActionsFor } from './survive-world-model.js';
import { groundHeight } from './survive-world-art.js';
import { createWorldRenderer } from './survive-world-renderer.js';
import { createWorldFeedback } from './survive-world-feedback.js';

/** Missing/unsupported canvas leaves the existing accessible adventure fully operational. */
export function mountSurviveWorld(documentRoot, requestJson, onAction, rendererFactory = createWorldRenderer, onAggro = () => {}) {
  const canvas = documentRoot.getElementById('surviveWorldCanvas');
  if (!canvas || typeof canvas.getContext !== 'function') return { setState() {}, dispose() {} };
  const shell = documentRoot.getElementById('surviveWorld');
  const feedback = documentRoot.getElementById('surviveWorldConnection');
  const actionList = documentRoot.getElementById('surviveWorldActions');
  const commandButton = documentRoot.getElementById('surviveCommandButton');
  const mainMenu = documentRoot.getElementById('surviveMainMenu');
  const labels = documentRoot.getElementById('surviveWorldLabels');
  const mapPlayer = documentRoot.getElementById('surviveMapPlayer');
  const locationLabel = documentRoot.getElementById('surviveWorldLocation');
  const target = documentRoot.getElementById('surviveWorldEnemyMarker');
  let renderer;
  function releasePresence() {
    return requestJson(API.survive.leaveIsland, { method: 'POST', body: '{}', redirectOnUnauthorized: false,
      signal: AbortSignal.timeout(4000) }).catch(() => {
      feedback.textContent = 'Could not leave the island. Reload before using the text adventure.';
    });
  }
  try { renderer = rendererFactory(canvas); }
  catch (error) {
    feedback.textContent = `${error.message} Use the camp illustration and action menus below.`;
    canvas.hidden = true;
    let fallbackSurvivorId;
    return {
      setState(nextState) {
        if (nextState && fallbackSurvivorId !== nextState.survivorId) {
          fallbackSurvivorId = nextState.survivorId;
          void releasePresence();
        }
      },
      dispose() {},
    };
  }
  documentRoot.getElementById('surviveCampScene').setAttribute('hidden', '');
  const worldFeedback = createWorldFeedback(documentRoot);
  shell.dataset.active = 'true';
  const view = documentRoot.defaultView;
  const motionPreference = view.matchMedia?.('(prefers-reduced-motion: reduce)');
  const pressed = new Set();
  const listeners = [];
  let state = null;
  let targetSelected = false;
  let busy = false;
  let disposed = false;
  let connected = false;
  let position = { x: 0, z: 0, heading: 0 };
  let confirmed = { ...position };
  let sequence = 0;
  let yaw = 0;
  let others = [];
  let wildlife = [];
  let encounter = null;
  let approachStartedAt = null;
  let gesture = null;
  let pulsePromise = null;
  let controller = null;
  let nextPulseAt = 0;
  let pulseInterval = 500;
  let frameId = 0;
  let previousFrameAt = 0;
  let controllerAxes = { right: 0, forward: 0, camera: 0 };
  let lastActionSignature = '';
  let lastActionsBlocked;
  let drag = null;
  let requestGeneration = 0;
  let desiredNameLabels = [];

  function listen(target, type, callback, options) {
    target.addEventListener(type, callback, options);
    listeners.push(() => target.removeEventListener(type, callback, options));
  }
  const isVisible = () => state && !documentRoot.hidden && !documentRoot.getElementById('surviveGame').hidden
    && !documentRoot.getElementById('surviveGame').inert
    && documentRoot.getElementById('surviveJoin').hidden;
  function stopWalking() { pressed.clear(); }
  function syncNameLabels() {
    const entries = [...LANDMARKS.map(landmark => ({ ...landmark, id: landmark.name, landmark: true })),
      ...others.map(other => ({ ...other, id: other.survivorId })),
      ...wildlife.map(animal => ({ ...animal, name: `${animal.kind.toLowerCase()} · ${animal.aggroMetres ? 'hostile' : 'passive'}` }))];
    const oldById = new Map(desiredNameLabels.map(label => [label.id, label]));
    desiredNameLabels = entries.map(entry => {
      const label = oldById.get(entry.id)?.element || documentRoot.createElement('span');
      const labelType = entry.landmark ? ' is-landmark' : entry.kind ? (entry.aggroMetres ? ' is-hostile' : ' is-passive') : '';
      label.className = `survive-world-label${labelType}`;
      label.textContent = entry.name;
      return { ...entry, element: label };
    });
    labels.replaceChildren(...desiredNameLabels.map(label => label.element));
  }

  async function sendPulse() {
    if (pulsePromise || !isVisible() || disposed) return pulsePromise;
    const generation = requestGeneration;
    const submitted = { ...position };
    const sentAt = Date.now();
    controller = new AbortController();
    const timeout = setTimeout(() => controller?.abort(), 4000);
    pulsePromise = (async () => {
      try {
        const snapshot = await requestJson(API.survive.movement, {
          method: 'POST', redirectOnUnauthorized: false, signal: controller.signal,
          body: JSON.stringify({ ...submitted, sequence: ++sequence }),
        });
        if (disposed || generation !== requestGeneration) return;
        if (!snapshot?.self || snapshot.self.survivorId !== state?.survivorId) throw new Error('The island session changed. Refresh your survivor.');
        sequence = Math.max(sequence, snapshot.self.sequence);
        confirmed = { x: snapshot.self.x, z: snapshot.self.z, heading: snapshot.self.heading };
        if (!connected || !snapshot.accepted || Math.hypot(submitted.x - confirmed.x, submitted.z - confirmed.z) > 0.2) {
          position = { ...confirmed };
        }
        connected = true;
        const previousOthers = new Map(others.map(other => [other.survivorId, other]));
        others = snapshot.others.map(other => ({ ...other,
          drawX: previousOthers.get(other.survivorId)?.drawX ?? other.x,
          drawZ: previousOthers.get(other.survivorId)?.drawZ ?? other.z,
        }));
        const previousWildlife = new Map(wildlife.map(animal => [animal.id, animal]));
        wildlife = (snapshot.wildlife || []).map(animal => ({ ...animal,
          drawX: previousWildlife.get(animal.id)?.drawX ?? animal.x,
          drawZ: previousWildlife.get(animal.id)?.drawZ ?? animal.z,
        }));
        if (snapshot.encounter?.id !== encounter?.id) approachStartedAt = null;
        encounter = snapshot.encounter || null;
        if (snapshot.self.status === 'COMBAT' && state?.status === 'EXPLORING') {
          stopWalking();
          onAggro();
        }
        syncNameLabels();
        pulseInterval = Math.max(500, pulseInterval * 0.9);
        feedback.textContent = `Live island · ${others.length + 1} online`;
      } catch (error) {
        if (disposed || generation !== requestGeneration) return;
        connected = false;
        position = { ...confirmed };
        others = [];
        wildlife = [];
        syncNameLabels();
        stopWalking();
        pulseInterval = Math.min(15000, Math.max(1500, pulseInterval * 2));
        feedback.textContent = `Connection paused. Reconnecting… ${error.message || ''}`;
      } finally {
        clearTimeout(timeout);
        pulsePromise = null;
        nextPulseAt = Math.max(sentAt + pulseInterval, Date.now() + 30);
      }
    })();
    return pulsePromise;
  }

  function selectTarget() {
    if (state?.status !== 'COMBAT') return;
    if (!targetSelected) {
      targetSelected = true;
      target.setAttribute('aria-pressed', 'true');
    }
    openCommands();
  }

  function closeCommands() {
    actionList.hidden = true;
    commandButton.setAttribute('aria-expanded', 'false');
  }

  function openCommands() {
    updateActions();
    if (commandButton.hidden) return;
    stopWalking();
    mainMenu.open = false;
    actionList.hidden = false;
    commandButton.setAttribute('aria-expanded', 'true');
    actionList.children[0]?.focus({ preventScroll: true });
  }

  async function interact(action) {
    if (state?.status === 'COMBAT') {
      if (action === 'SELECT_TARGET') {
        selectTarget();
      } else if (action === 'CHECK_TARGET' && targetSelected) {
        const danger = { HOG: 'Low threat', WOLF: 'Dangerous', BEAR: 'Severe threat' };
        const threat = danger[state.enemy] || 'Unknown threat';
        appendSurviveLogMessage(documentRoot, `${target.textContent} · ${threat}.`);
      } else if (!busy && targetActionsFor(state, targetSelected).includes(action) && state.actions.includes(action)) {
        onAction(action);
      }
      return;
    }
    if (!connected || busy || !state || !actionsAt(position, state.actions, wildlife).includes(action)) return;
    stopWalking();
    // Acknowledge the current landmark before sending its durable command; never retry commands.
    await pulsePromise;
    await sendPulse();
    if (!disposed && connected && actionsAt(confirmed, state.actions, wildlife).includes(action)) onAction(action);
  }

  function updateActions() {
    const available = state?.status === 'COMBAT' ? targetActionsFor(state, targetSelected)
      : state ? actionsAt(position, state.actions, wildlife) : [];
    const blocked = state?.status === 'COMBAT' ? busy : busy || !connected;
    const signature = available.join(',');
    if (signature === lastActionSignature) {
      if (blocked !== lastActionsBlocked) {
        for (const button of actionList.children) button.setAttribute('aria-disabled', String(blocked));
        lastActionsBlocked = blocked;
      }
      return;
    }
    lastActionSignature = signature;
    lastActionsBlocked = blocked;
    commandButton.hidden = available.length === 0;
    if (!available.length) closeCommands();
    const focusedAction = documentRoot.activeElement?.dataset?.worldAction;
    const buttons = available.map(action => {
      const button = documentRoot.createElement('button');
      button.type = 'button';
      button.textContent = ACTION_LABELS[action] || action;
      button.dataset.worldAction = action;
      button.setAttribute('aria-disabled', String(blocked));
      return button;
    });
    actionList.replaceChildren(...buttons);
    if (focusedAction) {
      (buttons.find(button => button.dataset.worldAction === focusedAction) || buttons[0] || canvas).focus({ preventScroll: true });
    }
  }

  listen(actionList, 'click', event => {
    const button = event.target.closest('[data-world-action]');
    if (button) void interact(button.dataset.worldAction);
  });
  listen(target, 'click', selectTarget);
  listen(commandButton, 'click', () => {
    if (actionList.hidden) openCommands();
    else closeCommands();
  });
  listen(mainMenu, 'toggle', () => {
    if (mainMenu.open) {
      stopWalking();
      closeCommands();
    }
  });
  listen(shell, 'keydown', event => {
    if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey
        || event.target.closest('input, select, textarea')) return;
    if (event.code === 'Minus' || event.code === 'NumpadSubtract') {
      event.preventDefault();
      if (event.repeat) return;
      mainMenu.open = !mainMenu.open;
      const focusTarget = mainMenu.open ? mainMenu.querySelector('.survive-main-menu-list summary') : canvas;
      focusTarget.focus({ preventScroll: true });
    } else if (event.code === 'Escape' && !event.target.closest('.survive-game-log')
        && (mainMenu.open || !actionList.hidden)) {
      event.preventDefault();
      mainMenu.open = false;
      closeCommands();
      canvas.focus({ preventScroll: true });
    } else if (event.code === 'ArrowUp' || event.code === 'ArrowDown') {
      const list = event.target.closest('#surviveWorldActions, .survive-main-menu-list');
      if (!list) return;
      const options = [...(list === actionList ? actionList.children : list.querySelectorAll(':scope > details > summary'))];
      const index = options.indexOf(event.target);
      if (index < 0) return;
      event.preventDefault();
      const step = event.code === 'ArrowUp' ? -1 : 1;
      options[(index + step + options.length) % options.length].focus();
    }
  });
  const directionKeys = new Map([['KeyW', 'forward'], ['ArrowUp', 'forward'], ['KeyS', 'back'], ['ArrowDown', 'back'],
    ['KeyA', 'left'], ['ArrowLeft', 'left'], ['KeyD', 'right'], ['ArrowRight', 'right']]);
  listen(canvas, 'keydown', event => {
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    const direction = directionKeys.get(event.code);
    if (direction) { event.preventDefault(); pressed.add(direction); }
    if (event.code === 'KeyQ') { event.preventDefault(); yaw -= 0.12; }
    if (event.code === 'KeyR') { event.preventDefault(); yaw += 0.12; }
    if ((event.code === 'Enter' || event.code === 'KeyE') && !event.repeat) {
      event.preventDefault();
      if (state?.status === 'COMBAT') selectTarget();
      else openCommands();
    }
    if (event.code === 'Escape') stopWalking();
  });
  listen(view, 'keyup', event => pressed.delete(directionKeys.get(event.code)));
  listen(canvas, 'blur', stopWalking);
  listen(view, 'blur', stopWalking);
  listen(documentRoot, 'visibilitychange', () => {
    stopWalking();
    if (documentRoot.hidden) { connected = false; controller?.abort(); }
    else nextPulseAt = 0;
  });
  listen(canvas, 'pointerdown', event => {
    canvas.focus({ preventScroll: true });
    drag = { id: event.pointerId, x: event.clientX };
    canvas.setPointerCapture(event.pointerId);
  });
  listen(canvas, 'pointermove', event => {
    if (drag?.id !== event.pointerId) return;
    yaw -= (event.clientX - drag.x) * 0.006;
    drag.x = event.clientX;
  });
  listen(canvas, 'pointerup', () => { drag = null; });
  listen(canvas, 'pointercancel', () => { drag = null; });
  listen(canvas, 'webglcontextlost', event => {
    event.preventDefault();
    stopWalking();
    connected = false;
    feedback.textContent = '3D graphics stopped. Reload to restore the island; the action menus remain available.';
    dispose();
    delete shell.dataset.active;
    actionList.replaceChildren();
    closeCommands();
    commandButton.hidden = true;
    void releasePresence();
    canvas.hidden = true;
    documentRoot.getElementById('surviveCampScene').removeAttribute('hidden');
  });

  function animate(timestamp) {
    if (disposed) return;
    const seconds = Math.min(0.05, Math.max(0, (timestamp - previousFrameAt) / 1000));
    previousFrameAt = timestamp;
    if (isVisible()) {
      const previousPosition = position;
      yaw += controllerAxes.camera * seconds * 2.4;
      if (connected && !busy && state.status === 'EXPLORING') {
        const right = Number(pressed.has('right')) - Number(pressed.has('left')) + controllerAxes.right;
        const forward = Number(pressed.has('forward')) - Number(pressed.has('back')) + controllerAxes.forward;
        // Stop prediction beyond a small unacknowledged window, including a stalled request.
        if (Math.hypot(position.x - confirmed.x, position.z - confirmed.z) < 5) {
          position = advancePosition(position, right, forward, yaw, seconds);
        }
      }
      for (const other of [...others, ...wildlife]) {
        other.drawX ??= other.x;
        other.drawZ ??= other.z;
        const dx = other.x - other.drawX;
        const dz = other.z - other.drawZ;
        other.moving = !motionPreference?.matches && Math.hypot(dx, dz) > 0.08;
        other.drawX += dx * Math.min(1, seconds * 8);
        other.drawZ += dz * Math.min(1, seconds * 8);
      }
      const moving = !motionPreference?.matches && Math.hypot(position.x - previousPosition.x, position.z - previousPosition.z) > 0.001;
      const enemy = encounterPosition(position, yaw, timestamp);
      const pose = gesture && !motionPreference?.matches ? { action: gesture.action, ageSeconds: (Date.now() - gesture.at) / 1000 } : null;
      const rendered = renderer.draw({ ...position, moving, animate: !motionPreference?.matches }, yaw, state,
        others.map(other => ({ ...other, x: other.drawX, z: other.drawZ })), timestamp / 1000,
        wildlife.map(animal => ({ ...animal, x: animal.drawX, z: animal.drawZ })), enemy, pose);
      worldFeedback.draw(rendered.project, enemy);
      for (const label of desiredNameLabels) {
        const animal = wildlife.find(candidate => candidate.id === label.id);
        const x = animal?.drawX ?? label.x;
        const z = animal?.drawZ ?? label.z;
        const y = groundHeight(x, z) + (label.landmark ? 4 : animal?.kind === 'BEAR' ? 3 : animal ? 2 : 3);
        const projected = rendered.project([x, y, z]);
        label.element.hidden = !projected.visible || (animal && Math.hypot(position.x - x, position.z - z) > 32);
        label.element.style.left = `${(projected.x + 1) * 50}%`;
        label.element.style.top = `${(1 - projected.y) * 50}%`;
      }
      if (connected) {
        mapPlayer.removeAttribute('hidden');
        mapPlayer.setAttribute('transform',
          `translate(${position.x.toFixed(1)} ${position.z.toFixed(1)}) rotate(${-position.heading * 180 / Math.PI})`);
      }
      const nearest = [...LANDMARKS].sort((a, b) => Math.hypot(a.x - position.x, a.z - position.z) - Math.hypot(b.x - position.x, b.z - position.z))[0];
      locationLabel.textContent = `${nearest.name} · ${Math.round(Math.hypot(nearest.x - position.x, nearest.z - position.z))} m`;
      updateActions();
      if (Date.now() >= nextPulseAt) void sendPulse();
    }
    frameId = view.requestAnimationFrame(animate);
  }

  function encounterPosition(player, cameraYaw, timestamp) {
    if (!encounter) return combatPosition(player, cameraYaw);
    if (state.nextStrikeAt) approachStartedAt ??= timestamp;
    const dx = player.x - encounter.x;
    const dz = player.z - encounter.z;
    const distance = Math.hypot(dx, dz);
    if (approachStartedAt === null || distance <= 2.1) return encounter;
    const progress = Math.min(1, (timestamp - approachStartedAt) / 1700);
    const fraction = (1 - 2.1 / distance) * progress;
    return { ...encounter, x: encounter.x + dx * fraction, z: encounter.z + dz * fraction,
      moving: progress < 1 && !motionPreference?.matches };
  }
  frameId = view.requestAnimationFrame(animate);
  function dispose() {
    if (disposed) return;
    disposed = true;
    requestGeneration++;
    controller?.abort();
    view.cancelAnimationFrame(frameId);
    for (const remove of listeners) remove();
    renderer.dispose();
    mapPlayer.setAttribute('hidden', '');
    worldFeedback.dispose();
    labels.replaceChildren();
  }
  return {
    setState(nextState, isBusy = false) {
      if (disposed) return;
      worldFeedback.acceptState(nextState);
      if (nextState && state?.survivorId === nextState.survivorId && state.nextStrikeAt
          && nextState.revision > state.revision && nextState.enemyHealth < state.enemyHealth
          && (nextState.status === 'COMBAT' || nextState.combatOutcome?.startsWith('You defeated '))) {
        gesture = { action: 'ATTACK', at: Date.now() };
      }
      if (nextState?.status !== 'COMBAT' || state?.survivorId !== nextState.survivorId || state?.enemy !== nextState.enemy) {
        targetSelected = false;
        target.setAttribute('aria-pressed', 'false');
      }
      if (nextState?.nextStrikeAt) {
        targetSelected = true;
        target.setAttribute('aria-pressed', 'true');
      }
      if (state?.survivorId !== nextState?.survivorId) {
        requestGeneration++;
        controller?.abort();
        connected = false;
        sequence = 0;
        position = { x: 0, z: 0, heading: 0 };
        confirmed = { ...position };
        others = [];
        wildlife = [];
        encounter = null;
        approachStartedAt = null;
        gesture = null;
        stopWalking();
        nextPulseAt = 0;
      }
      state = nextState;
      busy = isBusy;
      if (busy || state?.status !== 'EXPLORING') stopWalking();
    },
    animateAction(action) { gesture = { action, at: Date.now() }; },
    setControllerAxes(right, forward, camera) { controllerAxes = { right, forward, camera }; },
    stopInput() {
      stopWalking();
      controllerAxes = { right: 0, forward: 0, camera: 0 };
    },
    dispose,
  };
}
