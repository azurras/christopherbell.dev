import assert from 'node:assert/strict';
import test from 'node:test';
import {
  keepFocusInActionBar, mountActionMenus, mountPanelTabs, noteJournalEvents, syncActionMenus,
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

function tabsFixture() {
  const { documentRoot, h } = createFakeDocument();
  const names = ['Survivor', 'Camp', 'Journal', 'Guide'];
  const tablist = h('div', { role: 'tablist', id: 'surviveTabs' }, ...names.map((name, index) => h('button', {
    type: 'button', role: 'tab', id: `surviveTab${name}`, 'aria-controls': `survivePanel${name}`,
    'aria-selected': index === 0 ? 'true' : 'false', 'data-tab': name.toLowerCase(),
  }, name)));
  documentRoot.getElementById = id => documentRoot.body.querySelector(`#${id}`);
  documentRoot.body.appendChild(tablist);
  documentRoot.getElementById('surviveTabJournal').appendChild(h('span', { id: 'surviveJournalUnread' }));
  for (const name of names) {
    documentRoot.body.appendChild(h('section', { role: 'tabpanel', id: `survivePanel${name}` }));
  }
  const memory = new Map();
  const storage = { read: key => memory.get(key) ?? null, write: (key, value) => memory.set(key, value) };
  return { documentRoot, tablist, storage, memory, byId: id => documentRoot.getElementById(id) };
}

test('tabs select by click and arrow keys with one focusable tab and one visible panel', () => {
  const { documentRoot, tablist, storage, memory, byId } = tabsFixture();
  const tabs = mountPanelTabs(documentRoot, tablist, storage);
  try {
    assert.equal(byId('survivePanelSurvivor').hidden, false);
    assert.equal(byId('survivePanelCamp').hidden, true);
    assert.equal(byId('surviveTabSurvivor').getAttribute('tabindex'), '0');
    assert.equal(byId('surviveTabCamp').getAttribute('tabindex'), '-1');
    byId('surviveTabCamp').click();
    assert.equal(byId('surviveTabCamp').getAttribute('aria-selected'), 'true');
    assert.equal(byId('survivePanelCamp').hidden, false);
    assert.equal(byId('survivePanelSurvivor').hidden, true);
    assert.equal(memory.get('survive.tab'), 'camp');
    press(byId('surviveTabCamp'), 'ArrowRight');
    assert.equal(documentRoot.activeElement, byId('surviveTabJournal'));
    assert.equal(byId('survivePanelJournal').hidden, false);
    press(byId('surviveTabJournal'), 'End');
    assert.equal(documentRoot.activeElement, byId('surviveTabGuide'));
    press(byId('surviveTabGuide'), 'ArrowRight');
    assert.equal(documentRoot.activeElement, byId('surviveTabSurvivor'));
    press(byId('surviveTabSurvivor'), 'ArrowLeft');
    assert.equal(documentRoot.activeElement, byId('surviveTabGuide'));
    press(byId('surviveTabGuide'), 'Home');
    assert.equal(documentRoot.activeElement, byId('surviveTabSurvivor'));
  } finally { tabs.dispose(); }
});

test('the remembered tab is restored and broken storage falls back to the first tab', () => {
  const remembered = tabsFixture();
  remembered.memory.set('survive.tab', 'guide');
  mountPanelTabs(remembered.documentRoot, remembered.tablist, remembered.storage).dispose();
  assert.equal(remembered.byId('survivePanelGuide').hidden, false);
  const broken = tabsFixture();
  const throwingStorage = { read() { throw new Error('blocked'); }, write() { throw new Error('blocked'); } };
  const tabs = mountPanelTabs(broken.documentRoot, broken.tablist, throwingStorage);
  assert.equal(broken.byId('survivePanelSurvivor').hidden, false);
  broken.byId('surviveTabCamp').click();
  assert.equal(broken.byId('survivePanelCamp').hidden, false);
  tabs.dispose();
  const unknown = tabsFixture();
  unknown.memory.set('survive.tab', 'settings');
  mountPanelTabs(unknown.documentRoot, unknown.tablist, unknown.storage).dispose();
  assert.equal(unknown.byId('survivePanelSurvivor').hidden, false);
});

test('new journal events mark the Journal tab until it is opened', () => {
  const { documentRoot, tablist, storage, byId } = tabsFixture();
  const tabs = mountPanelTabs(documentRoot, tablist, storage);
  try {
    const unread = byId('surviveJournalUnread');
    noteJournalEvents(documentRoot, 10);
    assert.equal(unread.hidden, true);
    noteJournalEvents(documentRoot, 10);
    assert.equal(unread.hidden, true);
    noteJournalEvents(documentRoot, 11);
    assert.equal(unread.hidden, false);
    byId('surviveTabJournal').click();
    assert.equal(unread.hidden, true);
    noteJournalEvents(documentRoot, 12);
    assert.equal(unread.hidden, true);
    byId('surviveTabCamp').click();
    noteJournalEvents(documentRoot, 12);
    assert.equal(unread.hidden, true);
  } finally { tabs.dispose(); }
});
