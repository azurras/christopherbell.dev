/**
 * Login page behavior.
 *
 * Submits credentials to the login API, receives an HttpOnly cookie, and
 * redirects to a safe local target. Redirects authenticated users away.
 */
import pubsub from '../components/pubsub.js';
import { API } from '../lib/api.js';
import { fetchJson, isLoggedIn, safeRedirectTarget } from '../lib/util.js';

const alertBox = () => document.getElementById('loginAlert');

function redirectTarget() {
  const target = new URLSearchParams(window.location.search).get('redirect') || '/';
  return safeRedirectTarget(target);
}

/**
 * Perform login against the API.
 * @param {string} email account email
 * @param {string} password account password
 * @returns {Promise<void>}
 */
async function login(email, password) {
  await fetchJson(API.accounts.login, {
    method: 'POST',
    body: JSON.stringify({ email, password })
  });
}

/** Wire form submit and redirect rules once DOM is ready. */
document.addEventListener('DOMContentLoaded', () => {
  // If already logged in, redirect to the requested local page.
  if (isLoggedIn()) {
    window.location.href = redirectTarget();
    return;
  }
  const form = document.getElementById('loginForm');
  if (!form) return;

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const email = document.getElementById('email')?.value?.trim();
    const password = document.getElementById('password')?.value || '';
    const alert = alertBox();
    alert?.classList.add('d-none');
    try {
      await login(email, password);
      pubsub.publish('auth:login');
      window.location.href = redirectTarget();
    } catch (err) {
      if (alert) {
        alert.textContent = err.message;
        alert.classList.remove('d-none');
      }
    }
  });
});
