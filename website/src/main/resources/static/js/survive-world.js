/** Owns 3D input, prediction, acknowledged live presence, labels and cleanup for one page. */
import { API } from './lib/api.js';
import { advancePosition, actionsAt, ACTION_LABELS, LANDMARKS, combatPosition, targetActionsFor } from './survive-world-model.js';
import { createWorldRenderer } from './survive-world-renderer.js';
import { createWorldFeedback } from './survive-world-feedback.js';

/** Missing/unsupported canvas leaves the existing accessible adventure fully operational. */
export function mountSurviveWorld(documentRoot, requestJson, onAction, rendererFactory = createWorldRenderer) {
  const canvas = documentRoot.getElementById('surviveWorldCanvas');
  if (!canvas || typeof canvas.getContext !== 'function') return { setState() {}, dispose() {} };
  const shell = documentRoot.getElementById('surviveWorld');
  const feedback = documentRoot.getElementById('surviveWorldConnection');
  const actionList = documentRoot.getElementById('surviveWorldActions');
  const labels = documentRoot.getElementById('surviveWorldLabels');
  const directions = documentRoot.getElementById('surviveWorldDirections');
  const locationLabel = documentRoot.getElementById('surviveWorldLocation');
  const target = documentRoot.getElementById('surviveWorldEnemyMarker');
  const targetDetails = documentRoot.getElementById('surviveWorldTargetDetails');
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
    directions.hidden = true;
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
  let goal = null;
  let others = [];
  let pulsePromise = null;
  let controller = null;
  let nextPulseAt = 0;
  let pulseInterval = 500;
  let frameId = 0;
  let previousFrameAt = 0;
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
    && documentRoot.getElementById('surviveJoin').hidden;
  function stopWalking() { pressed.clear(); goal = null; }
  function syncNameLabels() {
    const entries = [...LANDMARKS.map(landmark => ({ ...landmark, id: landmark.name, landmark: true })),
      ...others.map(other => ({ ...other, id: other.survivorId }))];
    const oldById = new Map(desiredNameLabels.map(label => [label.id, label]));
    desiredNameLabels = entries.map(entry => {
      const label = oldById.get(entry.id)?.element || documentRoot.createElement('span');
      label.className = entry.landmark ? 'survive-world-label is-landmark' : 'survive-world-label';
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
        syncNameLabels();
        pulseInterval = Math.max(500, pulseInterval * 0.9);
        feedback.textContent = `Live island · ${others.length + 1} online`;
      } catch (error) {
        if (disposed || generation !== requestGeneration) return;
        connected = false;
        position = { ...confirmed };
        others = [];
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
    if (state?.status !== 'COMBAT' || targetSelected) return;
    targetSelected = true;
    target.setAttribute('aria-pressed', 'true');
    targetDetails.textContent = 'Engaged fights continue while you are away.';
  }

  async function interact(action) {
    if (state?.status === 'COMBAT') {
      if (action === 'SELECT_TARGET') {
        selectTarget();
      } else if (action === 'CHECK_TARGET' && targetSelected) {
        const danger = { HOG: 'Low threat', WOLF: 'Dangerous', BEAR: 'Severe threat' };
        targetDetails.textContent = `${danger[state.enemy] || 'Unknown threat'}. ${state.enemyHealth} health. Engaged fights continue while away.`;
      } else if (!busy && targetActionsFor(state, targetSelected).includes(action) && state.actions.includes(action)) {
        onAction(action);
      }
      return;
    }
    if (!connected || busy || !state || !actionsAt(position, state.actions).includes(action)) return;
    stopWalking();
    // Acknowledge the current landmark before sending its durable command; never retry commands.
    await pulsePromise;
    await sendPulse();
    if (!disposed && connected && actionsAt(confirmed, state.actions).includes(action)) onAction(action);
  }

  function updateActions() {
    const available = state?.status === 'COMBAT' ? targetActionsFor(state, targetSelected)
      : state ? actionsAt(position, state.actions) : [];
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
      (buttons.find(button => button.dataset.worldAction === focusedAction) || target).focus({ preventScroll: true });
    }
  }

  listen(actionList, 'click', event => {
    const button = event.target.closest('[data-world-action]');
    if (button) void interact(button.dataset.worldAction);
  });
  listen(target, 'click', selectTarget);
  listen(shell, 'click', event => {
    const destination = event.target.closest('[data-destination]');
    if (destination && connected && state?.status === 'EXPLORING') {
      goal = LANDMARKS[Number(destination.dataset.destination)];
      canvas.focus({ preventScroll: true });
    }
    const rotate = event.target.closest('[data-camera-turn]');
    if (rotate) yaw += Number(rotate.dataset.cameraTurn) * Math.PI / 6;
  });
  const directionKeys = new Map([['KeyW', 'forward'], ['ArrowUp', 'forward'], ['KeyS', 'back'], ['ArrowDown', 'back'],
    ['KeyA', 'left'], ['ArrowLeft', 'left'], ['KeyD', 'right'], ['ArrowRight', 'right']]);
  listen(canvas, 'keydown', event => {
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    const direction = directionKeys.get(event.code);
    if (direction) { event.preventDefault(); pressed.add(direction); goal = null; }
    if (event.code === 'KeyQ') { event.preventDefault(); yaw -= 0.12; }
    if (event.code === 'KeyR') { event.preventDefault(); yaw += 0.12; }
    if (event.code === 'KeyE' && !event.repeat) {
      event.preventDefault();
      if (state?.status === 'COMBAT') {
        selectTarget();
        target.focus({ preventScroll: true });
        return;
      }
      const first = actionsAt(position, state?.actions || [])[0];
      if (first) void interact(first);
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
  listen(directions, 'pointerdown', event => {
    const button = event.target.closest('[data-walk]');
    if (!button) return;
    event.preventDefault();
    goal = null;
    pressed.add(button.dataset.walk);
    button.setPointerCapture(event.pointerId);
  });
  for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) {
    listen(directions, type, () => pressed.clear());
  }
  listen(canvas, 'webglcontextlost', event => {
    event.preventDefault();
    stopWalking();
    connected = false;
    feedback.textContent = '3D graphics stopped. Reload to restore the island; the action menus remain available.';
    dispose();
    delete shell.dataset.active;
    actionList.replaceChildren();
    void releasePresence();
    canvas.hidden = true;
    directions.hidden = true;
    documentRoot.getElementById('surviveCampScene').removeAttribute('hidden');
  });

  function animate(timestamp) {
    if (disposed) return;
    const seconds = Math.min(0.05, Math.max(0, (timestamp - previousFrameAt) / 1000));
    previousFrameAt = timestamp;
    if (isVisible()) {
      if (connected && !busy && state.status === 'EXPLORING') {
        let right = Number(pressed.has('right')) - Number(pressed.has('left'));
        let forward = Number(pressed.has('forward')) - Number(pressed.has('back'));
        if (goal) {
          const dx = goal.x - position.x;
          const dz = goal.z - position.z;
          if (Math.hypot(dx, dz) < 1) goal = null;
          else {
            const distance = Math.hypot(dx, dz);
            right = (dx * Math.cos(yaw) - dz * Math.sin(yaw)) / distance;
            forward = (-dx * Math.sin(yaw) - dz * Math.cos(yaw)) / distance;
          }
        }
        // Stop prediction beyond a small unacknowledged window, including a stalled request.
        if (Math.hypot(position.x - confirmed.x, position.z - confirmed.z) < 5) {
          position = advancePosition(position, right, forward, yaw, seconds);
        }
      }
      for (const other of others) {
        other.drawX ??= other.x;
        other.drawZ ??= other.z;
        other.drawX += (other.x - other.drawX) * Math.min(1, seconds * 8);
        other.drawZ += (other.z - other.drawZ) * Math.min(1, seconds * 8);
      }
      const rendered = renderer.draw(position, yaw, state, others.map(other => ({ ...other, x: other.drawX, z: other.drawZ })), motionPreference?.matches ? null : worldFeedback.effect());
      worldFeedback.draw(rendered.project, position, combatPosition(position, yaw));
      for (const label of desiredNameLabels) {
        const projected = rendered.project([label.x, label.landmark ? 4 : 3, label.z]);
        label.element.hidden = !projected.visible;
        label.element.style.left = `${(projected.x + 1) * 50}%`;
        label.element.style.top = `${(1 - projected.y) * 50}%`;
      }
      const nearest = [...LANDMARKS].sort((a, b) => Math.hypot(a.x - position.x, a.z - position.z) - Math.hypot(b.x - position.x, b.z - position.z))[0];
      locationLabel.textContent = `${nearest.name} · ${Math.round(Math.hypot(nearest.x - position.x, nearest.z - position.z))} m`;
      updateActions();
      if (Date.now() >= nextPulseAt) void sendPulse();
    }
    frameId = view.requestAnimationFrame(animate);
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
    worldFeedback.dispose();
    labels.replaceChildren();
  }
  return {
    setState(nextState, isBusy = false) {
      if (disposed) return;
      worldFeedback.acceptState(nextState, isBusy);
      if (nextState?.status !== 'COMBAT' || state?.survivorId !== nextState.survivorId || state?.enemy !== nextState.enemy) {
        targetSelected = false;
        target.setAttribute('aria-pressed', 'false');
        targetDetails.textContent = '';
      }
      if (nextState?.nextStrikeAt) {
        targetSelected = true;
        target.setAttribute('aria-pressed', 'true');
        const details = nextState.defending ? 'Guard ready for the next exchange.' : 'Auto-attacking · Weapon skills cost 100 TP.';
        if (targetDetails.textContent !== details) targetDetails.textContent = details;
      } else if (targetSelected && state?.nextStrikeAt) {
        targetDetails.textContent = 'Automatic attacks stopped. Engage to resume.';
      }
      if (state?.survivorId !== nextState?.survivorId) {
        requestGeneration++;
        controller?.abort();
        connected = false;
        sequence = 0;
        position = { x: 0, z: 0, heading: 0 };
        confirmed = { ...position };
        others = [];
        stopWalking();
        nextPulseAt = 0;
      }
      state = nextState;
      busy = isBusy;
      if (busy || state?.status !== 'EXPLORING') stopWalking();
    },
    beginAction(action) { if (!disposed) worldFeedback.beginAction(action); },
    showError(message) { if (!disposed) worldFeedback.showError(message); },
    dispose,
  };
}
