const ALERT_TYPES = Object.freeze(['danger', 'info', 'success', 'warning']);

/** Render plain alert text into an explicit existing host. */
export function renderAlert(host, message, type = null) {
  if (!host) return false;
  host.textContent = message ?? '';
  host.classList.remove('d-none');
  if (type !== null) {
    host.classList.remove(...ALERT_TYPES.map(value => `alert-${value}`));
    const allowedType = ALERT_TYPES.includes(type) ? type : 'danger';
    host.classList.add(`alert-${allowedType}`);
  }
  return true;
}
