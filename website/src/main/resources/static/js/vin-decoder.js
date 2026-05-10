import { API } from './lib/api.js';
import { fetchJson } from './lib/util.js';

const form = document.getElementById('vinDecodeForm');
const input = document.getElementById('vinInput');
const button = document.getElementById('vinDecodeButton');
const alertBox = document.getElementById('vinDecodeAlert');
const result = document.getElementById('vinResult');
const jsonOutput = document.getElementById('vinJsonOutput');
const curlOutput = document.getElementById('vinCurlOutput');

function setText(id, value) {
  const el = document.getElementById(id);
  if (el) el.textContent = value || '-';
}

function plantText(decoded) {
  return [decoded.plantCity, decoded.plantState, decoded.plantCountry]
      .filter(Boolean)
      .join(', ');
}

function curlFor(vin) {
  const origin = window.location.origin;
  return [
    `curl -X POST '${origin}${API.vehicles.decodeVin}' \\`,
    `  -H 'Content-Type: application/json' \\`,
    `  -d '{"vin":"${vin}"}'`
  ].join('\n');
}

function showAlert(message) {
  if (!alertBox) return;
  alertBox.textContent = message;
  alertBox.classList.remove('d-none');
}

function hideAlert() {
  alertBox?.classList.add('d-none');
}

function render(decoded) {
  setText('vinMake', decoded.make);
  setText('vinModel', decoded.model);
  setText('vinYear', decoded.year ? String(decoded.year) : '');
  setText('vinBody', decoded.body);
  setText('vinPlant', plantText(decoded));

  const formattedJson = JSON.stringify(decoded, null, 2);
  if (jsonOutput) jsonOutput.textContent = formattedJson;
  if (curlOutput) curlOutput.textContent = curlFor(decoded.vin);
  result?.classList.remove('d-none');
}

async function copyText(el, buttonEl) {
  if (!el || !buttonEl) return;
  await navigator.clipboard.writeText(el.textContent || '');
  const original = buttonEl.textContent;
  buttonEl.textContent = 'Copied';
  setTimeout(() => { buttonEl.textContent = original; }, 1200);
}

form?.addEventListener('submit', async (event) => {
  event.preventDefault();
  hideAlert();
  const vin = (input?.value || '').trim().toUpperCase();
  if (!vin) {
    showAlert('Enter a VIN to decode.');
    return;
  }

  try {
    if (button) button.disabled = true;
    const decoded = await fetchJson(API.vehicles.decodeVin, {
      method: 'POST',
      body: JSON.stringify({ vin })
    });
    render(decoded);
  } catch (err) {
    showAlert(err.message || 'VIN decode failed.');
    result?.classList.add('d-none');
  } finally {
    if (button) button.disabled = false;
  }
});

input?.addEventListener('input', () => {
  input.value = input.value.toUpperCase().replace(/[^A-HJ-NPR-Z0-9]/g, '').slice(0, 17);
});

document.getElementById('copyJsonButton')?.addEventListener('click', () => {
  copyText(jsonOutput, document.getElementById('copyJsonButton'));
});

document.getElementById('copyCurlButton')?.addEventListener('click', () => {
  copyText(curlOutput, document.getElementById('copyCurlButton'));
});
