/** Use browser device signals. Window width and touch input alone also describe desktop hardware. */
export function isPhoneBrowser(navigator) {
  return navigator?.userAgentData?.mobile === true
    || /iPhone|iPod|Windows Phone|Android.*\bMobile\b/i.test(navigator?.userAgent || '');
}

/** Read only controllers with the browser's standard layout. Some browsers block this API. */
export function readStandardGamepad(navigator) {
  try {
    return Array.from(navigator.getGamepads?.() || []).find(pad => pad?.connected
      && pad.mapping === 'standard' && pad.axes.length >= 4 && pad.buttons.length >= 16) || null;
  } catch {
    return null;
  }
}

const CONFIRM = 0;
const CANCEL = 1;
const MAIN_MENU = 2;
const CHAT_LOG = 3;
const LOCK_TARGET = 10;
const RESET_CAMERA = 11;
const UP = 12;
const DOWN = 13;
const LEFT = 14;
const RIGHT = 15;
const DEAD_ZONE = 0.23;

function axis(value) {
  return Math.abs(value) >= DEAD_ZONE ? value : 0;
}

/** Share controller controls across devices and gate gameplay only on phones. */
export function mountSurviveController(documentRoot, world, onResume) {
  const view = documentRoot.defaultView;
  const phone = isPhoneBrowser(view?.navigator);
  const gamepadSupported = typeof view?.navigator?.getGamepads === 'function';
  if (!phone && !gamepadSupported) return { dispose() {} };

  const shell = documentRoot.getElementById('survivePlayShell');
  const game = documentRoot.getElementById('surviveGame');
  const join = documentRoot.getElementById('surviveJoin');
  const gate = documentRoot.getElementById('survivePhoneGate');
  const status = documentRoot.getElementById('survivePhoneStatus');
  const fullscreen = documentRoot.getElementById('surviveFullscreen');
  const canvas = documentRoot.getElementById('surviveWorldCanvas');
  const mainMenu = documentRoot.getElementById('surviveMainMenu');
  const commands = documentRoot.getElementById('surviveWorldActions');
  const log = documentRoot.getElementById('surviveGameLog');
  const scrollback = documentRoot.getElementById('surviveLogScrollback');
  const startOver = documentRoot.getElementById('surviveStartOver');
  const cancelJoin = documentRoot.getElementById('surviveCancel');
  let frame;
  let idlePoll;
  let ready = !phone;
  let lastButtons = [];
  let controllerIndex;
  let wasGameVisible = false;
  let fullscreenFailed = false;
  const fullscreenSupported = typeof shell.requestFullscreen === 'function' && documentRoot.fullscreenEnabled !== false;
  if (phone) shell.dataset.phone = 'true';

  function key(target, code, value = code) {
    target.dispatchEvent(new view.KeyboardEvent('keydown', { code, key: value, bubbles: true, cancelable: true }));
  }

  function currentMenu() {
    if (mainMenu.open) return mainMenu;
    if (!commands.hidden) return commands;
    return null;
  }

  function moveMenuFocus(step) {
    if (log.dataset.expanded === 'true' && log.contains(documentRoot.activeElement)) {
      scrollback.scrollBy(0, step * 32);
      return;
    }
    const scope = join.hidden ? currentMenu() : join;
    if (!scope) return;
    const options = [...scope.querySelectorAll('summary, button, a[href], input, select')].filter(element =>
      !element.disabled && element.getAttribute('aria-disabled') !== 'true' && element.getClientRects().length > 0);
    const index = options.indexOf(documentRoot.activeElement);
    const next = index < 0 ? (step > 0 ? options[0] : options.at(-1))
      : options[(index + step + options.length) % options.length];
    next?.focus({ preventScroll: true });
    next?.scrollIntoView({ block: 'nearest' });
  }

  function stepSelect(select, step) {
    const choice = select.selectedIndex + step;
    if (choice < 0 || choice >= select.options.length) return false;
    select.selectedIndex = choice;
    select.dispatchEvent(new view.Event('change', { bubbles: true }));
    return true;
  }

  function moveVertical(focused, step) {
    if (focused?.tagName === 'SELECT' && focused.size > 1 && stepSelect(focused, step)) return;
    moveMenuFocus(step);
  }

  function useButtons(buttons) {
    const pressed = index => buttons[index]?.pressed && !lastButtons[index];
    const focused = documentRoot.activeElement;
    if (pressed(UP)) moveVertical(focused, -1);
    if (pressed(DOWN)) moveVertical(focused, 1);
    if (pressed(LEFT) || pressed(RIGHT)) {
      const step = pressed(LEFT) ? -1 : 1;
      if (focused?.tagName === 'SELECT') {
        stepSelect(focused, step);
      } else if (join.hidden && !game.hidden && !currentMenu() && focused === canvas) {
        key(canvas, step > 0 ? 'Numpad0' : 'NumpadDecimal');
      } else moveMenuFocus(step);
    }
    if (pressed(CONFIRM)) {
      const target = documentRoot.activeElement;
      if (target !== canvas && (game.contains(target) || join.contains(target))) target.click();
      else if (!game.hidden && !startOver.hidden && !currentMenu()) startOver.click();
      else if (!game.hidden) key(canvas, 'Enter');
    }
    if (!join.hidden) {
      if (pressed(CANCEL) && !cancelJoin.hidden) cancelJoin.click();
      return;
    }
    if (game.hidden) return;
    if (pressed(MAIN_MENU)) key(canvas, 'Minus', '-');
    if (pressed(CHAT_LOG)) key(canvas, 'Equal', '+');
    if (!currentMenu() && (focused === canvas || focused === documentRoot.body)) {
      if (pressed(LOCK_TARGET)) key(canvas, 'NumpadMultiply', '*');
      if (pressed(RESET_CAMERA)) key(canvas, 'Home');
    }
    if (pressed(CANCEL)) {
      if (game.contains(focused) && focused !== canvas) key(focused, 'Escape');
      else key(canvas, 'Escape');
      if (!currentMenu() && log.dataset.expanded !== 'true') canvas.focus({ preventScroll: true });
    }
  }

  function syncPhoneGate(isFullscreen, controller) {
    const wasReady = ready;
    ready = isFullscreen && controller !== null && !canvas.hidden;
    if (shell.dataset.locked !== String(!ready)) {
      gate.hidden = ready;
      game.inert = !ready;
      join.inert = !ready;
      shell.dataset.locked = String(!ready);
    }
    const fullscreenLabel = isFullscreen ? 'Exit fullscreen' : 'Enter fullscreen';
    if (fullscreen.textContent !== fullscreenLabel) fullscreen.textContent = fullscreenLabel;
    fullscreen.disabled = !isFullscreen && (!fullscreenSupported || !gamepadSupported || canvas.hidden);
    const message = canvas.hidden ? '3D graphics are unavailable. Play on a desktop or laptop.'
      : !fullscreenSupported ? 'This browser cannot fullscreen the game. Play on a desktop or laptop.'
      : !gamepadSupported ? 'This browser cannot read controllers. Play on a desktop or laptop.'
      : fullscreenFailed && !isFullscreen ? 'Fullscreen was blocked. Try again in the browser directly.'
      : !isFullscreen ? 'Enter fullscreen, then press a button on your connected controller.'
      : 'Connect a supported controller and press a button to begin.';
    if (status.textContent !== message) status.textContent = message;

    if (ready !== wasReady) {
      world.stopInput?.();
      if (ready) {
        (game.hidden ? join.querySelector('input') : canvas)?.focus({ preventScroll: true });
        onResume();
      } else {
        fullscreen.focus({ preventScroll: true });
      }
    }
  }

  function update() {
    const controller = readStandardGamepad(view.navigator);
    if (phone) syncPhoneGate(documentRoot.fullscreenElement === shell, controller);
    if (ready && controller && !documentRoot.hidden) {
      const gameVisible = !game.hidden && join.hidden;
      if (phone && gameVisible && !wasGameVisible) canvas.focus({ preventScroll: true });
      wasGameVisible = gameVisible;
      if (controller.index === controllerIndex) useButtons(controller.buttons);
      const wantsToMove = controller.axes.slice(0, 4).some(value => axis(value));
      const focus = documentRoot.activeElement;
      if (!phone && wantsToMove && gameVisible && !currentMenu()
          && (focus === documentRoot.body || !focus)) canvas.focus({ preventScroll: true });
      const moving = gameVisible && documentRoot.activeElement === canvas && !currentMenu();
      world.setControllerAxes?.(moving ? axis(controller.axes[0]) : 0,
        moving ? -axis(controller.axes[1]) : 0, moving ? axis(controller.axes[2]) : 0,
        moving ? axis(controller.axes[3]) : 0);
      lastButtons = controller.buttons.map(button => Boolean(button?.pressed));
    } else {
      world.setControllerAxes?.(0, 0, 0, 0);
      wasGameVisible = false;
      lastButtons = [];
    }
    controllerIndex = ready ? controller?.index : undefined;
    return controller !== null;
  }

  async function enterFullscreen() {
    fullscreenFailed = false;
    try {
      if (documentRoot.fullscreenElement === shell) await documentRoot.exitFullscreen();
      else await shell.requestFullscreen();
    } catch {
      fullscreenFailed = true;
    }
    update();
  }

  function ignoreHardwareKeys(event) {
    if (event.isTrusted && !event.target.closest('input, select, textarea')) {
      event.preventDefault();
      event.stopPropagation();
    }
  }

  function poll() {
    if (update() || phone) frame = view.requestAnimationFrame(poll);
    else idlePoll = view.setTimeout(poll, 250);
  }

  if (phone) {
    fullscreen.addEventListener('click', enterFullscreen);
    documentRoot.addEventListener('fullscreenchange', update);
    game.addEventListener('keydown', ignoreHardwareKeys, true);
  }
  view.addEventListener('gamepaddisconnected', update);
  poll();
  return {
    dispose() {
      view.cancelAnimationFrame(frame);
      view.clearTimeout?.(idlePoll);
      view.removeEventListener('gamepaddisconnected', update);
      documentRoot.removeEventListener('fullscreenchange', update);
      fullscreen.removeEventListener('click', enterFullscreen);
      game.removeEventListener('keydown', ignoreHardwareKeys, true);
      world.stopInput?.();
    },
  };
}
