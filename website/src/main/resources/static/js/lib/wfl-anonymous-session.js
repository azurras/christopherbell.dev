export const WFL_ANONYMOUS_SESSION_KEY = 'cbellWflAnonymousSession';
export const WFL_ANONYMOUS_SESSION_TTL_MS = 30 * 60 * 1000;

function restaurantIds(value) {
  return Array.from(new Set((Array.isArray(value) ? value : [])
    .map((item) => String(item?.id ?? item ?? '').trim())
    .filter(Boolean)))
    .slice(0, 3);
}

function remove(storage) {
  storage.removeItem(WFL_ANONYMOUS_SESSION_KEY);
  return null;
}

function validStoredValue(value, now) {
  const ids = restaurantIds(value?.restaurantIds);
  const expiresAt = Number(value?.expiresAt);
  if (![2, 3].includes(value?.version)
    || ids.length === 0
    || !Number.isFinite(expiresAt)
    || expiresAt <= now) {
    return null;
  }
  return { version: 3, restaurantIds: ids, expiresAt };
}

export function readAnonymousWflSession(storage = localStorage, now = Date.now()) {
  let value;
  try {
    value = JSON.parse(storage.getItem(WFL_ANONYMOUS_SESSION_KEY) || 'null');
  } catch (_) {
    return remove(storage);
  }
  const current = validStoredValue(value, now);
  if (current) {
    storage.setItem(WFL_ANONYMOUS_SESSION_KEY, JSON.stringify(current));
    return current;
  }

  const legacyIds = restaurantIds(value?.restaurants);
  if (legacyIds.length === 0) return remove(storage);
  const migrated = {
    version: 3,
    restaurantIds: legacyIds,
    expiresAt: now + WFL_ANONYMOUS_SESSION_TTL_MS,
  };
  storage.setItem(WFL_ANONYMOUS_SESSION_KEY, JSON.stringify(migrated));
  return migrated;
}

export function writeAnonymousWflSession(
  restaurants,
  _selectedZipCode,
  storage = localStorage,
  now = Date.now(),
) {
  const ids = restaurantIds(restaurants);
  if (ids.length === 0) return remove(storage);
  const value = {
    version: 3,
    restaurantIds: ids,
    expiresAt: now + WFL_ANONYMOUS_SESSION_TTL_MS,
  };
  storage.setItem(WFL_ANONYMOUS_SESSION_KEY, JSON.stringify(value));
  return value;
}

export function clearAnonymousWflSession(storage = localStorage) {
  storage.removeItem(WFL_ANONYMOUS_SESSION_KEY);
}
