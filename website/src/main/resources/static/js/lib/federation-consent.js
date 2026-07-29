/** Validate the authenticated federation status boundary. */
export function normalizeFederationConsentStatus(value) {
  if (typeof value?.enabled !== 'boolean' || typeof value?.enrollmentAvailable !== 'boolean') {
    throw new Error('Invalid federation consent status.');
  }
  return {
    enabled: value.enabled,
    enrollmentAvailable: value.enrollmentAvailable,
  };
}

/** Resolve checkbox state without preventing an existing user from opting out. */
export function federationConsentControlModel(status, busy = false) {
  const normalized = normalizeFederationConsentStatus(status);
  const disabled = Boolean(busy || (!normalized.enrollmentAvailable && !normalized.enabled));
  let message = normalized.enabled
    ? 'Your public Void identity is discoverable on compatible social servers.'
    : 'Your public Void identity is not federated.';
  if (!normalized.enrollmentAvailable && !normalized.enabled) {
    message = 'Federation enrollment is not available on this server right now.';
  } else if (!normalized.enrollmentAvailable) {
    message = 'New enrollment is unavailable, but you can still turn off your existing identity.';
  } else if (busy) {
    message = 'Saving your federation choice…';
  }
  return { checked: normalized.enabled, disabled, message };
}
