/**
 * Side panel behavior for the Survive page: remembered collapsible sections, the journal's
 * new-events marker and the camp summary. Display only; renders never open or close sections.
 */
const SECTION_NAMES = ['camp', 'journal', 'guide'];
const SECTION_STORAGE_PREFIX = 'survive.section.';

/** Restores each section's remembered open state and remembers changes. Returns its disposer. */
export function mountPanelSections(documentRoot, storage = browserStorage()) {
  const sections = SECTION_NAMES.map(name => ({ name, element: sectionElement(documentRoot, name) }));
  for (const { name, element } of sections) {
    element.open = readSafely(storage, SECTION_STORAGE_PREFIX + name) === 'open';
  }
  const togglers = sections.map(({ name, element }) => {
    const onToggle = () => {
      writeSafely(storage, SECTION_STORAGE_PREFIX + name, element.open ? 'open' : 'closed');
      if (name === 'journal' && element.open) {
        markJournalRead(documentRoot);
      }
    };
    element.addEventListener('toggle', onToggle);
    return { element, onToggle };
  });
  const fullJournalLink = documentRoot.getElementById('surviveFullJournal');
  const openJournal = event => {
    event.preventDefault();
    const journal = sectionElement(documentRoot, 'journal');
    journal.open = true;
    writeSafely(storage, SECTION_STORAGE_PREFIX + 'journal', 'open');
    markJournalRead(documentRoot);
    journal.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
  };
  fullJournalLink.addEventListener('click', openJournal);
  return {
    dispose() {
      for (const { element, onToggle } of togglers) {
        element.removeEventListener('toggle', onToggle);
      }
      fullJournalLink.removeEventListener('click', openJournal);
    },
  };
}

/** Marks the journal heading when the world changed since the journal was last open. */
export function noteJournalEvents(documentRoot, worldRevision) {
  const journal = sectionElement(documentRoot, 'journal');
  const revision = String(worldRevision);
  journal.dataset.latestRevision = revision;
  if (journal.open || journal.dataset.seenRevision === undefined) {
    journal.dataset.seenRevision = revision;
  }
  documentRoot.getElementById('surviveJournalUnread').hidden = journal.dataset.seenRevision === revision;
}

/** One line describing the shared camp, for the closed Camp heading. */
export function summarizeCamp(state) {
  return [countOf(state.shelters, 'shelter'), countOf(state.boats, 'boat'), countOf(state.survivors.length, 'survivor')]
    .join(' · ');
}

function markJournalRead(documentRoot) {
  const journal = sectionElement(documentRoot, 'journal');
  if (journal.dataset.latestRevision !== undefined) {
    journal.dataset.seenRevision = journal.dataset.latestRevision;
  }
  documentRoot.getElementById('surviveJournalUnread').hidden = true;
}

function sectionElement(documentRoot, name) {
  return documentRoot.getElementById(`surviveSection${name[0].toUpperCase()}${name.slice(1)}`);
}

function countOf(count, singular) {
  return `${count} ${count === 1 ? singular : `${singular}s`}`;
}

function browserStorage() {
  return {
    read: key => globalThis.localStorage?.getItem(key) ?? null,
    write: (key, value) => globalThis.localStorage?.setItem(key, value),
  };
}

/** Storage can be missing or blocked (private windows, previews); the page works without it. */
function readSafely(storage, key) {
  try {
    return storage.read(key);
  } catch (storageFailure) {
    return null;
  }
}

function writeSafely(storage, key, value) {
  try {
    storage.write(key, value);
  } catch (storageFailure) {
    // The section still opens or closes; it just will not be remembered.
  }
}
