const MATERIALS = [
  { id: 'food', name: 'Food', detail: 'Restores up to 2 HP and 5 fullness. Use while exploring.' },
  { id: 'wood', name: 'Wood', detail: 'Material for crafting weapons and building camp structures.' },
  { id: 'stone', name: 'Stone', detail: 'Material for crafting a stone axe or spear.' },
];

/** Old snapshots can omit delay while the browser and server update independently. */
export function attackDelayLabel(milliseconds) {
  return Number.isFinite(milliseconds) && milliseconds > 0 ? ` · ${milliseconds / 1000}s delay` : '';
}

/** Render the carried items from a confirmed snapshot without replacing the focused list control. */
export function renderSurviveItems(documentRoot, state, pending) {
  const list = documentRoot.getElementById('surviveItemList');
  if (!list) return;
  const entries = MATERIALS.map(item => ({ ...item, quantity: state[item.id] }))
    .filter(item => item.quantity > 0);
  for (const weapon of state.packedWeapons ?? []) {
    entries.push({
      id: weapon.id,
      name: weapon.name.charAt(0).toUpperCase() + weapon.name.slice(1),
      quantity: 1,
      detail: `+${weapon.damageBonus} damage${attackDelayLabel(weapon.attackDelayMilliseconds)} · ${weapon.durability} uses left. Equip from Equipment → Main.`,
    });
  }
  const sortByName = list.dataset.sort === 'name';
  if (sortByName) entries.sort((first, second) => first.name.localeCompare(second.name));
  const sort = documentRoot.getElementById('surviveItemSort');
  sort.textContent = sortByName ? 'Sort: Name' : 'Sort: Type';
  sort.disabled = entries.length < 2;
  const signature = JSON.stringify(entries.map(({ id, quantity }) => [id, quantity]));
  if (list.dataset.entries !== signature || list.dataset.owner !== state.survivorId) {
    const previousId = list.dataset.owner === state.survivorId ? list.value : '';
    const options = entries.map(item => {
      const option = documentRoot.createElement('option');
      option.value = item.id;
      option.textContent = `${item.name} ×${item.quantity}`;
      return option;
    });
    list.replaceChildren(...options);
    list.value = entries.some(item => item.id === previousId) ? previousId : entries[0]?.id ?? '';
    list.dataset.entries = signature;
    list.dataset.owner = state.survivorId;
  }
  const selected = entries.find(item => item.id === list.value);
  documentRoot.getElementById('surviveItemDetail').textContent = selected?.detail ?? 'Your pack is empty.';
  const use = documentRoot.getElementById('surviveItemUse');
  const wasFocused = documentRoot.activeElement === use;
  use.hidden = selected?.id !== 'food';
  use.disabled = use.hidden || !state.actions.includes('EAT');
  use.setAttribute('aria-disabled', String(pending));
  if (wasFocused && use.disabled) list.focus({ preventScroll: true });
}
