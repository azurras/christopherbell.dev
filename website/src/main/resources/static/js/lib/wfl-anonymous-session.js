export const WFL_ANONYMOUS_SESSION_KEY = 'cbellWflAnonymousSession';
export const WFL_ANONYMOUS_SESSION_TTL_MS = 30 * 60 * 1000;

function zipCode(value) {
  const candidate = String(value || '').trim();
  if (/^\d{5}$/.test(candidate)) return candidate;
  if (/^\d{5}-\d{4}$/.test(candidate)) return candidate.slice(0, 5);
  return '';
}

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

function validV2(value, now) {
  const ids = restaurantIds(value?.restaurantIds);
  const expiresAt = Number(value?.expiresAt);
  if (value?.version !== 2 || ids.length === 0 || !Number.isFinite(expiresAt) || expiresAt <= now) {
    return null;
  }
  return { version: 2, restaurantIds: ids, zipCode: zipCode(value.zipCode), expiresAt };
}

export function readAnonymousWflSession(storage = localStorage, now = Date.now()) {
  let value;
  try {
    value = JSON.parse(storage.getItem(WFL_ANONYMOUS_SESSION_KEY) || 'null');
  } catch (_) {
    return remove(storage);
  }
  const current = validV2(value, now);
  if (current) {
    storage.setItem(WFL_ANONYMOUS_SESSION_KEY, JSON.stringify(current));
    return current;
  }

  const legacyIds = restaurantIds(value?.restaurants);
  if (legacyIds.length === 0) return remove(storage);
  const migrated = {
    version: 2,
    restaurantIds: legacyIds,
    zipCode: zipCode(value?.zipCode),
    expiresAt: now + WFL_ANONYMOUS_SESSION_TTL_MS,
  };
  storage.setItem(WFL_ANONYMOUS_SESSION_KEY, JSON.stringify(migrated));
  return migrated;
}

export function writeAnonymousWflSession(
  restaurants,
  selectedZipCode,
  storage = localStorage,
  now = Date.now(),
) {
  const ids = restaurantIds(restaurants);
  if (ids.length === 0) return remove(storage);
  const value = {
    version: 2,
    restaurantIds: ids,
    zipCode: zipCode(selectedZipCode),
    expiresAt: now + WFL_ANONYMOUS_SESSION_TTL_MS,
  };
  storage.setItem(WFL_ANONYMOUS_SESSION_KEY, JSON.stringify(value));
  return value;
}

export function clearAnonymousWflSession(storage = localStorage) {
  storage.removeItem(WFL_ANONYMOUS_SESSION_KEY);
}
