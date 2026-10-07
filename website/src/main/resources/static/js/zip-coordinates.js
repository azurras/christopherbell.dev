import { API } from './lib/api.js';
import { renderAlert } from './lib/status-message.js';
import { fetchJson } from './lib/util.js';

const hasDocument = typeof document !== 'undefined';
const lookupForm = hasDocument ? document.getElementById('zipCoordinateForm') : null;
const zipInput = hasDocument ? document.getElementById('zipCoordinateInput') : null;
const lookupButton = hasDocument ? document.getElementById('zipCoordinateButton') : null;
const alertBox = hasDocument ? document.getElementById('zipCoordinateAlert') : null;
const resultPanel = hasDocument ? document.getElementById('zipCoordinateResult') : null;

/** Normalize ZIP or ZIP+4 input into the five-digit lookup key the API accepts. */
export function normalizeZipInput(enteredZipCode) {
  const trimmedZipCode = String(enteredZipCode || '').trim();
  if (/^\d{5}$/.test(trimmedZipCode)) return trimmedZipCode;
  if (/^\d{5}-\d{4}$/.test(trimmedZipCode)) return trimmedZipCode.slice(0, 5);
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

function setTextOf(elementId, text) {
  const element = document.getElementById(elementId);
  if (element) element.textContent = text || '-';
}

function showAlert(message) {
  renderAlert(alertBox, message);
}

function hideAlert() {
  alertBox?.classList.add('d-none');
}

function renderCoordinate(coordinate) {
  const zipCode = coordinate?.zipCode || normalizeZipInput(zipInput?.value);
  const apiUrl = zipCoordinateApiUrl(window.location.origin, zipCode);

  setTextOf('zipCoordinateCode', zipCode);
  setTextOf('zipLatitude', coordinate?.latitude == null ? '' : String(coordinate.latitude));
  setTextOf('zipLongitude', coordinate?.longitude == null ? '' : String(coordinate.longitude));
  setTextOf('zipSource', coordinate?.source);
  setTextOf('zipSourceYear', coordinate?.sourceYear == null ? '' : String(coordinate.sourceYear));
  setTextOf('zipApiUrl', apiUrl);
  setTextOf('zipCurlOutput', zipCoordinateCurl(window.location.origin, zipCode));
  resultPanel?.classList.remove('d-none');
}

async function copyTextOf(sourceElement, copyButton) {
  if (!sourceElement || !copyButton) return;
  try {
    await navigator.clipboard.writeText(sourceElement.textContent || '');
    const originalLabel = copyButton.textContent;
    copyButton.textContent = 'Copied';
    setTimeout(() => { copyButton.textContent = originalLabel; }, 1200);
  } catch {
    showAlert('Unable to copy text. Please copy it manually.');
  }
}

lookupForm?.addEventListener('submit', async (event) => {
  event.preventDefault();
  hideAlert();
  const zipCode = normalizeZipInput(zipInput?.value);
  if (!zipCode) {
    resultPanel?.classList.add('d-none');
    showAlert('Enter a five-digit ZIP code or ZIP+4.');
    return;
  }

  try {
    if (lookupButton) lookupButton.disabled = true;
    const coordinate = await fetchJson(API.location.zipCoordinate(zipCode));
    renderCoordinate(coordinate);
  } catch (lookupFailure) {
    resultPanel?.classList.add('d-none');
    showAlert(lookupFailure.message || 'ZIP coordinate lookup failed.');
  } finally {
    if (lookupButton) lookupButton.disabled = false;
  }
});

zipInput?.addEventListener('input', () => {
  zipInput.value = zipInput.value.replace(/[^\d-]/g, '').slice(0, 10);
});

if (hasDocument) {
  document.getElementById('copyZipApiButton')?.addEventListener('click', () => {
    copyTextOf(document.getElementById('zipApiUrl'), document.getElementById('copyZipApiButton'));
  });

  document.getElementById('copyZipCurlButton')?.addEventListener('click', () => {
    copyTextOf(document.getElementById('zipCurlOutput'), document.getElementById('copyZipCurlButton'));
  });
}
