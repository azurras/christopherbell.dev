import assert from 'node:assert/strict';
import test from 'node:test';
import { mountPanelSections, noteJournalEvents, summarizeCamp } from '../../main/resources/static/js/survive-panel.js';
import { createFakeDocument } from './support/fake-dom.js';

function panelFixture() {
  const { documentRoot, h } = createFakeDocument();
  const section = name => {
    const details = h('details', { id: `surviveSection${name}` }, h('summary', {}, name));
    details.open = false;
    return details;
  };
  const fullJournal = h('a', { href: '#surviveSectionJournal', id: 'surviveFullJournal' }, 'Full journal');
  const journal = section('Journal');
  journal.children[0].appendChild(h('span', { id: 'surviveJournalUnread' }));
  for (const element of [fullJournal, section('Camp'), journal, section('Guide')]) {
    documentRoot.body.appendChild(element);
  }
  const memory = new Map();
  const storage = { read: key => memory.get(key) ?? null, write: (key, value) => memory.set(key, value) };
  const byId = id => documentRoot.getElementById(id);
  /** Mimics the browser: the user opens or closes a section, then `toggle` fires. */
  const toggle = (name, isOpen) => {
    byId(`surviveSection${name}`).open = isOpen;
    byId(`surviveSection${name}`).dispatch('toggle');
  };
  return { documentRoot, storage, memory, byId, toggle };
}

test('sections start closed and remember being opened or closed', () => {
  const first = panelFixture();
  const panel = mountPanelSections(first.documentRoot, first.storage);
  try {
    assert.equal(first.byId('surviveSectionCamp').open, false);
    assert.equal(first.byId('surviveSectionGuide').open, false);
    first.toggle('Camp', true);
    first.toggle('Guide', true);
    first.toggle('Guide', false);
    assert.equal(first.memory.get('survive.section.camp'), 'open');
    assert.equal(first.memory.get('survive.section.guide'), 'closed');
  } finally { panel.dispose(); }

  const returning = panelFixture();
  returning.memory.set('survive.section.camp', 'open');
  returning.memory.set('survive.section.guide', 'closed');
  mountPanelSections(returning.documentRoot, returning.storage).dispose();
  assert.equal(returning.byId('surviveSectionCamp').open, true);
  assert.equal(returning.byId('surviveSectionGuide').open, false);
  assert.equal(returning.byId('surviveSectionJournal').open, false);
});

test('blocked storage leaves every section closed and still lets players open them', () => {
  const { documentRoot, byId, toggle } = panelFixture();
  const blocked = { read() { throw new Error('blocked'); }, write() { throw new Error('blocked'); } };
  const panel = mountPanelSections(documentRoot, blocked);
  try {
    assert.equal(byId('surviveSectionCamp').open, false);
    toggle('Camp', true);
    assert.equal(byId('surviveSectionCamp').open, true);
  } finally { panel.dispose(); }
});

test('the Full journal link opens the journal section', () => {
  const { documentRoot, storage, memory, byId } = panelFixture();
  const panel = mountPanelSections(documentRoot, storage);
  try {
    const click = byId('surviveFullJournal').dispatch('click');
    assert.equal(click.defaultPrevented, true);
    assert.equal(byId('surviveSectionJournal').open, true);
    assert.equal(memory.get('survive.section.journal'), 'open');
  } finally { panel.dispose(); }
  byId('surviveSectionJournal').open = false;
  byId('surviveFullJournal').dispatch('click');
  assert.equal(byId('surviveSectionJournal').open, false);
});

test('new events mark the closed journal until it is opened', () => {
  const { documentRoot, storage, byId, toggle } = panelFixture();
  const panel = mountPanelSections(documentRoot, storage);
  try {
    const unread = byId('surviveJournalUnread');
    noteJournalEvents(documentRoot, 10);
    assert.equal(unread.hidden, true);
    noteJournalEvents(documentRoot, 11);
    assert.equal(unread.hidden, false);
    toggle('Journal', true);
    assert.equal(unread.hidden, true);
    noteJournalEvents(documentRoot, 12);
    assert.equal(unread.hidden, true);
    toggle('Journal', false);
    noteJournalEvents(documentRoot, 12);
    assert.equal(unread.hidden, true);
    noteJournalEvents(documentRoot, 13);
    assert.equal(unread.hidden, false);
  } finally { panel.dispose(); }
});

test('the camp summary counts structures and survivors in plain words', () => {
  assert.equal(summarizeCamp({ shelters: 2, boats: 1, survivors: ['A', 'B', 'C'] }), '2 shelters · 1 boat · 3 survivors');
  assert.equal(summarizeCamp({ shelters: 1, boats: 0, survivors: ['A'] }), '1 shelter · 0 boats · 1 survivor');
});
