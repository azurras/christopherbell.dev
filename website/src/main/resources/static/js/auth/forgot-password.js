/**
 * Forgot password page behavior.
 */
import { API } from '../lib/api.js';

const alertBox = () => document.getElementById('forgotPasswordAlert');

function showAlert(message, type) {
  const alert = alertBox();
  if (!alert) return;
  alert.textContent = message;
  alert.classList.remove('d-none', 'alert-danger', 'alert-success');
  alert.classList.add(type === 'success' ? 'alert-success' : 'alert-danger');
}

async function requestPasswordReset(email) {
  const resp = await fetch(API.accounts.passwordResetRequest, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email })
  });
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok || !data.success) {
    const msg = data?.messages?.[0]?.description || 'Unable to request a password reset.';
    throw new Error(msg);
  }
  return data.payload;
}

document.addEventListener('DOMContentLoaded', () => {
  const form = document.getElementById('forgotPasswordForm');
  if (!form) return;

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    alertBox()?.classList.add('d-none');
    try {
      const email = document.getElementById('email')?.value?.trim();
      const message = await requestPasswordReset(email);
      showAlert(message, 'success');
      form.reset();
    } catch (err) {
      showAlert(err.message, 'danger');
    }
  });
});
