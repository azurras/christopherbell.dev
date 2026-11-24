/** Own the two command pages and the cursor inside the currently open window. */
export function mountMainMenu(documentRoot) {
  const menu = documentRoot.getElementById('surviveMainMenu');
  const list = menu?.querySelector?.('.survive-main-menu-list');
  const pager = list?.querySelector('.survive-menu-pages');
  if (!pager) return () => {};
  const entries = [...list.children].filter(entry => entry !== pager);
  const controls = 'button, summary, select, input, a[href]';
  const remembered = new Map();
  let page = 1;

  function rootRows() {
    return entries.filter(entry => !entry.hidden).map(entry => entry.querySelector('summary') ?? entry);
  }

  function showPage(next) {
    page = next;
    for (const entry of entries) {
      entry.hidden = Number(entry.dataset.menuPage ?? 1) !== page;
      if (entry.hidden && entry.open) entry.open = false;
    }
    pager.querySelector('span').textContent = page + ' / 2';
  }

  function focusRoot() {
    (remembered.get(page) ?? rootRows()[0]).focus();
  }

  function switchPage() {
    showPage(3 - page);
    focusRoot();
  }

  function visibleControls(scope) {
    return [...scope.querySelectorAll(controls)].filter(row => !row.disabled
      && row.getAttribute('aria-disabled') !== 'true' && row.getClientRects().length);
  }

  function onFocus(event) {
    if (rootRows().includes(event.target)) remembered.set(page, event.target);
  }

  function onToggle() {
    if (menu.open) focusRoot();
    else for (const child of list.querySelectorAll('details[open]')) child.open = false;
  }

  function onClick(event) {
    const back = event.target.closest('[data-menu-back]');
    if (back) {
      const category = back.closest('details');
      category.open = false;
      category.querySelector('summary').focus();
      return;
    }
    if (pager.contains(event.target.closest('button'))) {
      switchPage();
      return;
    }
    const summary = event.target.closest('summary');
    if (!summary || !list.contains(summary)) return;
    event.preventDefault();
    const window = summary.parentElement;
    window.open = !window.open;
    if (!window.open) return;
    const panel = summary.nextElementSibling;
    const first = window.querySelector('#surviveItemList')
      ?? visibleControls(window).find(row => row !== summary && !row.hasAttribute('data-menu-back'));
    if (first) first.focus();
    else if (panel) {
      panel.tabIndex = -1;
      panel.focus();
    }
  }

  function onKey(event) {
    if (!menu.open || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
    const target = event.target;
    if (event.key === 'Enter' && target.matches('summary') && list.contains(target)) {
      event.preventDefault();
      if (!event.repeat) target.click();
      return;
    }
    if (event.key === 'Escape') {
      const drop = documentRoot.getElementById('surviveDropConfirm');
      if (drop && !drop.hidden && list.contains(drop) && drop.getClientRects().length) {
        event.preventDefault();
        documentRoot.getElementById('surviveCancelDrop').click();
        documentRoot.getElementById('surviveItemDrop').focus();
        return;
      }
      const window = target.closest('details[open]');
      if (target.closest('.survive-item-commands')) {
        event.preventDefault();
        documentRoot.getElementById('surviveItemList').focus();
        return;
      }
      if (window && window !== menu) {
        event.preventDefault();
        window.open = false;
        window.querySelector('summary').focus();
      }
      return;
    }
    const panel = target.closest('.survive-main-menu-panel');
    if (panel && target.matches('select[size]') && event.key === 'Enter') {
      const commands = panel.querySelector('.survive-item-commands');
      event.preventDefault();
      if (commands) visibleControls(commands)[0]?.focus();
      return;
    }
    if (!panel && ['ArrowLeft', 'ArrowRight'].includes(event.key)) {
      event.preventDefault();
      if (!event.repeat) switchPage();
      return;
    }
    if (!['ArrowUp', 'ArrowDown'].includes(event.key)) return;
    if (target.matches('input, select') && event.isTrusted) return;
    if (target === panel && panel.scrollHeight > panel.clientHeight) {
      event.preventDefault();
      panel.scrollBy(0, event.key === 'ArrowDown' ? 48 : -48);
      return;
    }
    const nested = target.closest('.survive-main-menu-panel details[open]');
    const confirmation = target.closest('#surviveDropConfirm');
    const rows = panel ? visibleControls(confirmation ?? nested ?? panel) : rootRows();
    if (!rows.length) return;
    const index = rows.indexOf(target);
    const step = event.key === 'ArrowDown' ? 1 : -1;
    event.preventDefault();
    rows[index < 0 ? 0 : (index + step + rows.length) % rows.length].focus();
  }

  showPage(1);
  pager.hidden = false;
  menu.dataset.paged = 'true';
  menu.addEventListener('focusin', onFocus);
  menu.addEventListener('toggle', onToggle);
  menu.addEventListener('click', onClick);
  menu.addEventListener('keydown', onKey);
  return () => {
    menu.removeEventListener('focusin', onFocus);
    menu.removeEventListener('toggle', onToggle);
    menu.removeEventListener('click', onClick);
    menu.removeEventListener('keydown', onKey);
    delete menu.dataset.paged;
    pager.hidden = true;
    for (const entry of entries) entry.hidden = false;
  };
}
