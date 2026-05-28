import { API } from './lib/api.js';
import { fetchJson } from './lib/util.js';

const hasDocument = typeof document !== 'undefined';
const form = hasDocument ? document.getElementById('zipCoordinateForm') : null;
const input = hasDocument ? document.getElementById('zipCoordinateInput') : null;
const button = hasDocument ? document.getElementById('zipCoordinateButton') : null;
const alertBox = hasDocument ? document.getElementById('zipCoordinateAlert') : null;
const result = hasDocument ? document.getElementById('zipCoordinateResult') : null;

/** Normalize ZIP or ZIP+4 input into the five-digit lookup key the API accepts. */
export function normalizeZipInput(value) {
  const zipCode = String(value || '').trim();
  if (/^\d{5}$/.test(zipCode)) return zipCode;
  if (/^\d{5}-\d{4}$/.test(zipCode)) return zipCode.slice(0, 5);
  return '';
}

/** Build a same-origin API URL for display and copy actions. */
export function zipCoordinateApiUrl(origin, zipCode) {
  return `${String(origin || '').replace(/\/$/, '')}${API.location.zipCoordinate(zipCode)}`;
}

/** Build a curl command that exercises the public ZIP coordinate endpoint. */
export function zipCoordinateCurl(origin, zipCode) {
  return `curl '${zipCoordinateApiUrl(origin, zipCode)}'`;
}

function setText(id, value) {
  const element = document.getElementById(id);
  if (element) element.textContent = value || '-';
}

function showAlert(message) {
  if (!alertBox) return;
  alertBox.textContent = message;
  alertBox.classList.remove('d-none');
}

function hideAlert() {
  alertBox?.classList.add('d-none');
}

function renderCoordinate(coordinate) {
  const zipCode = coordinate?.zipCode || normalizeZipInput(input?.value);
  const apiUrl = zipCoordinateApiUrl(window.location.origin, zipCode);

  setText('zipCoordinateCode', zipCode);
  setText('zipLatitude', coordinate?.latitude == null ? '' : String(coordinate.latitude));
  setText('zipLongitude', coordinate?.longitude == null ? '' : String(coordinate.longitude));
  setText('zipSource', coordinate?.source);
  setText('zipSourceYear', coordinate?.sourceYear == null ? '' : String(coordinate.sourceYear));
  setText('zipApiUrl', apiUrl);
  setText('zipCurlOutput', zipCoordinateCurl(window.location.origin, zipCode));
  result?.classList.remove('d-none');
}

async function copyElementText(element, buttonElement) {
  if (!element || !buttonElement) return;
  await navigator.clipboard.writeText(element.textContent || '');
  const original = buttonElement.textContent;
  buttonElement.textContent = 'Copied';
  setTimeout(() => { buttonElement.textContent = original; }, 1200);
}

form?.addEventListener('submit', async (event) => {
  event.preventDefault();
  hideAlert();
  const zipCode = normalizeZipInput(input?.value);
  if (!zipCode) {
    result?.classList.add('d-none');
    showAlert('Enter a five-digit ZIP code or ZIP+4.');
    return;
  }

  try {
    if (button) button.disabled = true;
    const coordinate = await fetchJson(API.location.zipCoordinate(zipCode));
    renderCoordinate(coordinate);
  } catch (err) {
    result?.classList.add('d-none');
    showAlert(err.message || 'ZIP coordinate lookup failed.');
  } finally {
    if (button) button.disabled = false;
  }
});

input?.addEventListener('input', () => {
  input.value = input.value.replace(/[^\d-]/g, '').slice(0, 10);
});

if (hasDocument) {
  document.getElementById('copyZipApiButton')?.addEventListener('click', () => {
    copyElementText(document.getElementById('zipApiUrl'), document.getElementById('copyZipApiButton'));
  });

  document.getElementById('copyZipCurlButton')?.addEventListener('click', () => {
    copyElementText(document.getElementById('zipCurlOutput'), document.getElementById('copyZipCurlButton'));
  });
}
