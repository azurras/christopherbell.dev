const MAX_LOG_LINES = 60;

/** Append one message from a local interaction, such as Check or a rejected command. */
export function appendSurviveLogMessage(documentRoot, message) {
  appendLines(documentRoot, [message]);
}

/** Each world revision records one journal line, including lines with identical text. */
export function syncSurviveLog(documentRoot, { events, worldRevision }) {
  const list = documentRoot.getElementById('surviveRecentEvents');
  const previousRevision = list.dataset.worldRevision;
  if (previousRevision !== undefined && worldRevision <= Number(previousRevision)) return;
  const unseenCount = previousRevision === undefined ? events.length : worldRevision - Number(previousRevision);
  const lines = unseenCount > events.length ? ['Some earlier world events are no longer available.'] : [];
  if (unseenCount > 0) lines.push(...events.slice(-unseenCount));
  appendLines(documentRoot, lines, previousRevision === undefined);
  list.dataset.worldRevision = String(worldRevision);
}

function appendLines(documentRoot, lines, initial = false) {
  const list = documentRoot.getElementById('surviveRecentEvents');
  const scrollback = documentRoot.getElementById('surviveLogScrollback');
  const followsLatest = initial || scrollback.scrollHeight - scrollback.clientHeight - scrollback.scrollTop <= 4;
  for (const line of lines) {
    const item = documentRoot.createElement('li');
    item.textContent = line;
    list.appendChild(item);
  }
  while (list.children.length > MAX_LOG_LINES) list.firstElementChild.remove();
  if (followsLatest) scrollback.scrollTop = scrollback.scrollHeight;
}
