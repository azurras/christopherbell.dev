/**
 * Reset password page behavior.
 */
import { API } from '../lib/api.js';

const alertBox = () => document.getElementById('resetPasswordAlert');

function showAlert(message, type) {
  const alert = alertBox();
  if (!alert) return;
  alert.textContent = message;
  alert.classList.remove('d-none', 'alert-danger', 'alert-success');
  alert.classList.add(type === 'success' ? 'alert-success' : 'alert-danger');
}

async function resetPassword(token, password) {
  const resp = await fetch(API.accounts.passwordResetConfirm, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, password })
  });
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok || !data.success) {
    const msg = data?.messages?.[0]?.description || 'Unable to reset password.';
    throw new Error(msg);
  }
  return data.payload;
}

document.addEventListener('DOMContentLoaded', () => {
  const form = document.getElementById('resetPasswordForm');
  if (!form) return;

  const token = new URLSearchParams(window.location.search).get('token') || '';
  if (!token) {
    showAlert('This password reset link is missing a token.', 'danger');
    form.querySelector('button[type="submit"]')?.setAttribute('disabled', 'disabled');
    return;
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    alertBox()?.classList.add('d-none');

    const password = document.getElementById('password')?.value || '';
    const confirmPassword = document.getElementById('confirmPassword')?.value || '';
    if (password !== confirmPassword) {
      showAlert('Passwords do not match.', 'danger');
      return;
    }

    try {
      const message = await resetPassword(token, password);
      showAlert(message, 'success');
      form.reset();
      setTimeout(() => {
        window.location.href = '/login';
      }, 1200);
    } catch (err) {
      showAlert(err.message, 'danger');
    }
  });
});
