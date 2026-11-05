const SECTION_NAMES = ['camp', 'guide'];
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
    };
    element.addEventListener('toggle', onToggle);
    return { element, onToggle };
  });
  return {
    dispose() {
      for (const { element, onToggle } of togglers) {
        element.removeEventListener('toggle', onToggle);
      }
    },
  };
}

/** One line describing the shared camp, for the closed Camp heading. */
export function summarizeCamp(state) {
  return [countOf(state.shelters, 'shelter'), countOf(state.boats, 'boat'), countOf(state.survivors.length, 'survivor')]
    .join(' · ');
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
