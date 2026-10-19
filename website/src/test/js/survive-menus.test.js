import assert from 'node:assert/strict';
import test from 'node:test';
import {
  keepFocusInActionBar, mountActionMenus, syncActionMenus,
} from '../../main/resources/static/js/survive-menus.js';
import { createFakeDocument } from './support/fake-dom.js';

function actionBarFixture() {
  const { documentRoot, h } = createFakeDocument();
  const move = action => h('button', { type: 'button', role: 'menuitem', 'data-action': action }, action);
  const menu = (name, ...items) => h('div', { class: 'survive-menu' },
    h('button', {
      type: 'button', id: `surviveMenu${name}Button`, 'aria-haspopup': 'menu', 'aria-expanded': 'false',
      'aria-controls': `surviveMenu${name}`, 'data-menu-button': '',
    }, name, h('span', { 'data-menu-count': '' })),
    h('div', { id: `surviveMenu${name}`, role: 'menu', 'aria-labelledby': `surviveMenu${name}Button` }, ...items));
  const craftList = h('div', { id: 'surviveMenuCraft', role: 'menu' },
    move('CRAFT_CLUB'), move('CRAFT_AXE'), move('CRAFT_SPEAR'));
  const actionBar = h('div', { id: 'surviveActionBar' },
    menu('Explore', move('GATHER'), move('GATHER_STONE'), move('HUNT')),
    menu('Build', move('BUILD_SHELTER'), move('BUILD_BOAT'),
      h('button', {
        type: 'button', role: 'menuitem', id: 'surviveCraftButton', 'aria-haspopup': 'menu',
        'aria-expanded': 'false', 'aria-controls': 'surviveMenuCraft', 'data-submenu-button': '',
      }, 'Craft'),
      craftList),
    menu('Camp', move('EAT'), move('REST'), move('GUARD'), move('SLEEP'), move('ESCAPE')));
  const outside = h('button', { type: 'button', id: 'outside' }, 'Elsewhere');
  documentRoot.body.appendChild(actionBar);
  documentRoot.body.appendChild(outside);
  for (const list of actionBar.querySelectorAll('[role="menu"]')) {
    list.hidden = true;
  }
  const byId = id => documentRoot.getElementById(id);
  const item = action => actionBar.querySelector(`[data-action="${action}"]`);
  /** Mimics renderSurviveState: only allowed moves are shown, then menus are synced. */
  const allow = (...actions) => {
    for (const control of actionBar.querySelectorAll('[data-action]')) {
      control.hidden = !actions.includes(control.dataset.action);
    }
    syncActionMenus(documentRoot, actionBar);
  };
  return { documentRoot, actionBar, outside, byId, item, allow };
}

const press = (element, key) => element.dispatch('keydown', { key });

test('menu buttons count only allowed moves and disable an empty category', () => {
  const { actionBar, byId, allow } = actionBarFixture();
  const menus = mountActionMenus(actionBar.ownerDocument, actionBar);
  try {
    allow('GATHER', 'HUNT', 'EAT');
    const countOf = name => byId(`surviveMenu${name}Button`).querySelector('[data-menu-count]').textContent;
    assert.equal(countOf('Explore'), '2');
    assert.equal(countOf('Build'), '0');
    assert.equal(byId('surviveMenuBuildButton').disabled, true);
    assert.equal(byId('surviveMenuCampButton').disabled, false);
    assert.equal(byId('surviveCraftButton').hidden, true);
    allow('BUILD_SHELTER', 'CRAFT_CLUB');
    assert.equal(countOf('Build'), '2');
    assert.equal(byId('surviveCraftButton').hidden, false);
  } finally { menus.dispose(); }
});

test('clicking a menu button opens its list, focuses the first allowed move and closes other menus', () => {
  const { documentRoot, actionBar, byId, item, allow } = actionBarFixture();
  const menus = mountActionMenus(documentRoot, actionBar);
  try {
    allow('GATHER_STONE', 'HUNT', 'EAT');
    byId('surviveMenuExploreButton').click();
    assert.equal(byId('surviveMenuExploreButton').getAttribute('aria-expanded'), 'true');
    assert.equal(byId('surviveMenuExplore').hidden, false);
    assert.equal(documentRoot.activeElement, item('GATHER_STONE'));
    byId('surviveMenuCampButton').click();
    assert.equal(byId('surviveMenuExplore').hidden, true);
    assert.equal(byId('surviveMenuExploreButton').getAttribute('aria-expanded'), 'false');
    assert.equal(documentRoot.activeElement, item('EAT'));
    byId('surviveMenuCampButton').click();
    assert.equal(byId('surviveMenuCamp').hidden, true);
  } finally { menus.dispose(); }
});

test('arrow keys move through allowed moves and Escape returns focus to the menu button', () => {
  const { documentRoot, actionBar, byId, item, allow } = actionBarFixture();
  const menus = mountActionMenus(documentRoot, actionBar);
  try {
    allow('GATHER', 'GATHER_STONE', 'HUNT');
    const button = byId('surviveMenuExploreButton');
    assert.equal(press(button, 'ArrowDown').defaultPrevented, true);
    assert.equal(documentRoot.activeElement, item('GATHER'));
    press(item('GATHER'), 'ArrowDown');
    assert.equal(documentRoot.activeElement, item('GATHER_STONE'));
    press(item('GATHER_STONE'), 'End');
    assert.equal(documentRoot.activeElement, item('HUNT'));
    press(item('HUNT'), 'ArrowDown');
    assert.equal(documentRoot.activeElement, item('GATHER'));
    press(item('GATHER'), 'ArrowUp');
    assert.equal(documentRoot.activeElement, item('HUNT'));
    press(item('HUNT'), 'Home');
    assert.equal(documentRoot.activeElement, item('GATHER'));
    press(item('GATHER'), 'Escape');
    assert.equal(byId('surviveMenuExplore').hidden, true);
    assert.equal(documentRoot.activeElement, button);
    press(button, 'ArrowUp');
    assert.equal(documentRoot.activeElement, item('HUNT'));
    press(item('HUNT'), 'Tab');
    assert.equal(byId('surviveMenuExplore').hidden, true);
  } finally { menus.dispose(); }
});

test('the craft submenu opens with Arrow Right or a click and closes with Arrow Left', () => {
  const { documentRoot, actionBar, byId, item, allow } = actionBarFixture();
  const menus = mountActionMenus(documentRoot, actionBar);
  try {
    allow('BUILD_SHELTER', 'CRAFT_AXE', 'CRAFT_SPEAR');
    press(byId('surviveMenuBuildButton'), 'ArrowDown');
    press(item('BUILD_SHELTER'), 'ArrowDown');
    const craft = byId('surviveCraftButton');
    assert.equal(documentRoot.activeElement, craft);
    press(craft, 'ArrowRight');
    assert.equal(craft.getAttribute('aria-expanded'), 'true');
    assert.equal(byId('surviveMenuCraft').hidden, false);
    assert.equal(documentRoot.activeElement, item('CRAFT_AXE'));
    press(item('CRAFT_AXE'), 'ArrowDown');
    assert.equal(documentRoot.activeElement, item('CRAFT_SPEAR'));
    press(item('CRAFT_SPEAR'), 'ArrowLeft');
    assert.equal(byId('surviveMenuCraft').hidden, true);
    assert.equal(documentRoot.activeElement, craft);
    assert.equal(byId('surviveMenuBuild').hidden, false);
    craft.click();
    assert.equal(byId('surviveMenuCraft').hidden, false);
    press(item('CRAFT_AXE'), 'Escape');
    assert.equal(byId('surviveMenuCraft').hidden, true);
    assert.equal(documentRoot.activeElement, craft);
  } finally { menus.dispose(); }
});

test('choosing a move or clicking outside closes every menu', () => {
  const { documentRoot, actionBar, outside, byId, item, allow } = actionBarFixture();
  const menus = mountActionMenus(documentRoot, actionBar);
  try {
    allow('BUILD_SHELTER', 'CRAFT_CLUB');
    byId('surviveMenuBuildButton').click();
    byId('surviveCraftButton').click();
    item('CRAFT_CLUB').click();
    assert.equal(byId('surviveMenuBuild').hidden, true);
    assert.equal(byId('surviveMenuCraft').hidden, true);
    assert.equal(documentRoot.activeElement, byId('surviveMenuBuildButton'));
    byId('surviveMenuBuildButton').click();
    outside.click();
    assert.equal(byId('surviveMenuBuild').hidden, true);
  } finally { menus.dispose(); }
});

test('a poll keeps an open menu open and moves focus only when the focused move disappears', () => {
  const { documentRoot, actionBar, byId, item, allow } = actionBarFixture();
  const menus = mountActionMenus(documentRoot, actionBar);
  try {
    allow('GATHER', 'GATHER_STONE', 'HUNT');
    press(byId('surviveMenuExploreButton'), 'ArrowDown');
    press(item('GATHER'), 'ArrowDown');
    allow('GATHER', 'GATHER_STONE', 'HUNT', 'EAT');
    assert.equal(byId('surviveMenuExplore').hidden, false);
    assert.equal(documentRoot.activeElement, item('GATHER_STONE'));
    allow('GATHER', 'HUNT');
    assert.equal(byId('surviveMenuExplore').hidden, false);
    assert.equal(documentRoot.activeElement, item('GATHER'));
    allow('EAT');
    assert.equal(byId('surviveMenuExplore').hidden, true);
    assert.equal(byId('surviveMenuExploreButton').getAttribute('aria-expanded'), 'false');
  } finally { menus.dispose(); }
});

test('focus moves to an enabled menu button when the focused menu empties', () => {
  const { documentRoot, actionBar, byId, allow } = actionBarFixture();
  const menus = mountActionMenus(documentRoot, actionBar);
  try {
    allow('GATHER', 'CRAFT_AXE');
    byId('surviveMenuBuildButton').focus();
    allow('GATHER');
    assert.equal(byId('surviveMenuBuildButton').disabled, true);
    assert.equal(documentRoot.activeElement, byId('surviveMenuExploreButton'));
  } finally { menus.dispose(); }
});

test('focus that was in the bar moves to the first usable control when the bar changes shape', () => {
  const { documentRoot, h } = createFakeDocument();
  const explore = h('button', { type: 'button', id: 'explore' }, 'Explore');
  const menus = h('div', { id: 'menus' }, explore);
  const attack = h('button', { type: 'button', 'data-action': 'ATTACK' }, 'Attack');
  const startOver = h('button', { type: 'button', id: 'startOver' }, 'Start a new survivor');
  const actionBar = h('div', { id: 'surviveActionBar' }, menus, h('div', {}, attack, startOver));
  documentRoot.body.appendChild(actionBar);
  attack.hidden = true;
  startOver.hidden = true;
  explore.focus();
  keepFocusInActionBar(documentRoot, actionBar);
  assert.equal(documentRoot.activeElement, explore);
  menus.hidden = true;
  attack.hidden = false;
  keepFocusInActionBar(documentRoot, actionBar);
  assert.equal(documentRoot.activeElement, attack);
  attack.hidden = true;
  startOver.hidden = false;
  keepFocusInActionBar(documentRoot, actionBar);
  assert.equal(documentRoot.activeElement, startOver);
});

test('disposing removes every listener, including the outside-click listener', () => {
  const { documentRoot, actionBar, byId, allow } = actionBarFixture();
  const menus = mountActionMenus(documentRoot, actionBar);
  allow('GATHER');
  menus.dispose();
  byId('surviveMenuExploreButton').click();
  assert.equal(byId('surviveMenuExplore').hidden, true);
  assert.equal(documentRoot.listeners.get('click')?.size ?? 0, 0);
});
