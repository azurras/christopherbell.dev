/**
 * Menu behavior for the Survive action bar. Display only: which moves exist is decided by the
 * server snapshot, which `renderSurviveState` applies by hiding moves before `syncActionMenus` runs.
 * Menus follow the menu-button pattern.
 */
/** Wires every menu button, item and submenu inside the action bar. Returns its disposer. */
export function mountActionMenus(documentRoot, actionBar) {
  const onClick = event => {
    const menuButton = event.target.closest('[data-menu-button]');
    if (menuButton && actionBar.contains(menuButton)) {
      if (!menuButton.disabled) {
        toggleMenu(documentRoot, actionBar, menuButton);
      }
      return;
    }
    const submenuButton = event.target.closest('[data-submenu-button]');
    if (submenuButton) {
      if (isExpanded(submenuButton)) {
        closeList(documentRoot, submenuButton);
      }
      else {
        openList(documentRoot, submenuButton, 'first');
      }
      return;
    }
    const chosenMove = event.target.closest('[data-action]');
    const chosenMenu = chosenMove?.closest('[role="menu"]');
    if (chosenMenu) {
      const topButton = topMenuButtonFor(documentRoot, actionBar, chosenMenu);
      closeAllMenus(documentRoot, actionBar);
      topButton?.focus();
    }
  };
  const onKeydown = event => {
    const menuButton = event.target.closest('[data-menu-button]');
    if (menuButton && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
      event.preventDefault();
      if (!menuButton.disabled) {
        closeAllMenus(documentRoot, actionBar);
        openList(documentRoot, menuButton, event.key === 'ArrowDown' ? 'first' : 'last');
      }
      return;
    }
    const menu = event.target.closest('[role="menu"]');
    if (menu && actionBar.contains(menu)) {
      handleMenuKey(documentRoot, actionBar, menu, event);
    }
  };
  const onDocumentClick = event => {
    if (!actionBar.contains(event.target)) {
      closeAllMenus(documentRoot, actionBar);
    }
  };
  actionBar.addEventListener('click', onClick);
  actionBar.addEventListener('keydown', onKeydown);
  documentRoot.addEventListener('click', onDocumentClick);
  return {
    dispose() {
      actionBar.removeEventListener('click', onClick);
      actionBar.removeEventListener('keydown', onKeydown);
      documentRoot.removeEventListener('click', onDocumentClick);
    },
  };
}

/**
 * Brings menu buttons in line with the moves the latest render left visible: counts, disabled empty
 * menus, the Craft submenu and focus. Open menus stay open unless they emptied.
 */
export function syncActionMenus(documentRoot, actionBar) {
  repairFocus(documentRoot, actionBar);
  const menuButtons = [...actionBar.querySelectorAll('[data-menu-button]')];
  const focusedMenuButton = menuButtons.find(button => button === documentRoot.activeElement);
  for (const menuButton of menuButtons) {
    const list = listControlledBy(documentRoot, menuButton);
    for (const submenuButton of list.querySelectorAll('[data-submenu-button]')) {
      const submenuIsEmpty = visibleMoves(listControlledBy(documentRoot, submenuButton)).length === 0;
      if (submenuIsEmpty && isExpanded(submenuButton)) {
        closeList(documentRoot, submenuButton);
      }
      submenuButton.hidden = submenuIsEmpty;
    }
    const moveCount = visibleMoves(list).length;
    menuButton.querySelector('[data-menu-count]').textContent = String(moveCount);
    menuButton.disabled = moveCount === 0;
    if (moveCount === 0 && isExpanded(menuButton)) {
      closeList(documentRoot, menuButton);
    }
  }
  // A move that empties its own menu disables the button holding focus; keep focus in the bar.
  if (focusedMenuButton?.disabled) {
    menuButtons.find(button => !button.disabled)?.focus();
  }
}

/**
 * After a render changes the bar's shape (a fight starts, sleep, the end of the game), returns
 * focus that was in the bar to its first usable control instead of letting it fall to the page.
 */
export function keepFocusInActionBar(documentRoot, actionBar) {
  const focused = documentRoot.activeElement;
  const focusIsUsable = Boolean(focused) && actionBar.contains(focused) && !focused.disabled
    && !focused.closest('[hidden]');
  if (focusIsUsable) {
    return;
  }
  const usableControls = [...actionBar.querySelectorAll('button')]
    .filter(control => !control.disabled && !control.closest('[hidden]'));
  usableControls[0]?.focus();
}

function handleMenuKey(documentRoot, actionBar, menu, event) {
  const items = focusableItems(menu);
  const index = items.indexOf(event.target);
  const submenuButton = event.target.closest('[data-submenu-button]');
  const parentButton = buttonControlling(documentRoot, actionBar, menu);
  switch (event.key) {
    case 'ArrowDown':
    case 'ArrowUp':
    case 'Home':
    case 'End': {
      event.preventDefault();
      const targetIndex = {
        ArrowDown: (index + 1) % items.length,
        ArrowUp: (index - 1 + items.length) % items.length,
        Home: 0,
        End: items.length - 1,
      }[event.key];
      items[targetIndex]?.focus();
      return;
    }
    case 'ArrowRight':
      if (submenuButton) {
        event.preventDefault();
        openList(documentRoot, submenuButton, 'first');
      }
      return;
    case 'ArrowLeft':
      if (parentButton?.hasAttribute('data-submenu-button')) {
        event.preventDefault();
        closeList(documentRoot, parentButton);
        parentButton.focus();
      }
      return;
    case 'Escape':
      event.preventDefault();
      closeList(documentRoot, parentButton);
      parentButton.focus();
      return;
    case 'Tab':
      closeAllMenus(documentRoot, actionBar);
      return;
    default:
  }
}

function toggleMenu(documentRoot, actionBar, menuButton) {
  const wasOpen = isExpanded(menuButton);
  closeAllMenus(documentRoot, actionBar);
  if (!wasOpen) {
    openList(documentRoot, menuButton, 'first');
  }
}

function openList(documentRoot, button, focusTarget) {
  button.setAttribute('aria-expanded', 'true');
  const list = listControlledBy(documentRoot, button);
  list.hidden = false;
  const items = focusableItems(list);
  (focusTarget === 'first' ? items[0] : items.at(-1))?.focus();
}

/** Closes a menu or submenu and everything nested inside it. */
function closeList(documentRoot, button) {
  const list = listControlledBy(documentRoot, button);
  for (const nestedButton of list.querySelectorAll('[aria-haspopup="menu"]')) {
    closeList(documentRoot, nestedButton);
  }
  button.setAttribute('aria-expanded', 'false');
  list.hidden = true;
}

function closeAllMenus(documentRoot, actionBar) {
  for (const menuButton of actionBar.querySelectorAll('[data-menu-button]')) {
    closeList(documentRoot, menuButton);
  }
}

/** Keeps focus on a visible move when a render hides the focused one. */
function repairFocus(documentRoot, actionBar) {
  const focused = documentRoot.activeElement;
  if (!focused || !actionBar.contains(focused) || !focused.hidden) {
    return;
  }
  const menu = focused.closest('[role="menu"]');
  const replacement = menu ? focusableItems(menu)[0] : null;
  (replacement ?? (menu ? topMenuButtonFor(documentRoot, actionBar, menu) : null))?.focus();
}

function listControlledBy(documentRoot, button) {
  return documentRoot.getElementById(button.getAttribute('aria-controls'));
}

function buttonControlling(documentRoot, actionBar, menu) {
  return actionBar.querySelector(`[aria-controls="${menu.id}"]`);
}

function topMenuButtonFor(documentRoot, actionBar, menu) {
  let button = buttonControlling(documentRoot, actionBar, menu);
  while (button?.hasAttribute('data-submenu-button')) {
    button = buttonControlling(documentRoot, actionBar, button.closest('[role="menu"]'));
  }
  return button;
}

function isExpanded(button) {
  return button.getAttribute('aria-expanded') === 'true';
}

/** Items that belong to this menu itself, not to a submenu nested inside it. */
function ownItems(menu) {
  return [...menu.querySelectorAll('[role="menuitem"]')].filter(item => item.closest('[role="menu"]') === menu);
}

function focusableItems(menu) {
  return ownItems(menu).filter(item => !item.hidden && !item.disabled);
}

/** Moves left visible anywhere in a menu, including its submenus. */
function visibleMoves(menu) {
  return [...menu.querySelectorAll('[data-action]')].filter(move => !move.hidden);
}
