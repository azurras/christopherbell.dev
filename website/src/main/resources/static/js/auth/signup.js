/**
 * Signup page behavior.
 *
 * Submits new account details to the API and redirects to login on success.
 * Redirects authenticated users away.
 */
import { API } from '../lib/api.js';
import { safeRedirectTarget } from '../lib/util.js';
const alertBox = () => document.getElementById('signupAlert');

function redirectTarget() {
  const target = new URLSearchParams(window.location.search).get('redirect') || '/';
  return safeRedirectTarget(target);
}

/**
 * Create a new account via API.
 * @param {{email:string,username:string,firstName?:string,lastName?:string,password:string}} payload
 * @returns {Promise<object>} created account detail
 */
async function signup(payload) {
  const resp = await fetch(API.accounts.create, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok || !data.success) {
    const msg = data?.messages?.[0]?.description || 'Sign up failed. Please try again.';
    throw new Error(msg);
  }
  return data.payload; // account detail
}

/** Wire form submit and redirect rules once DOM is ready. */
document.addEventListener('DOMContentLoaded', () => {
  // If already logged in, redirect to the requested local page.
  if (localStorage.getItem('cbellLoginToken')) {
    window.location.href = redirectTarget();
    return;
  }
  const form = document.getElementById('signupForm');
  if (!form) return;

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const payload = {
      email: document.getElementById('email')?.value?.trim(),
      username: document.getElementById('username')?.value?.trim(),
      firstName: document.getElementById('firstName')?.value?.trim() || null,
      lastName: document.getElementById('lastName')?.value?.trim() || null,
      password: document.getElementById('password')?.value || ''
    };
    const alert = alertBox();
    alert?.classList.add('d-none');
    try {
      await signup(payload);
      window.location.href = `/login?redirect=${encodeURIComponent(redirectTarget())}`;
    } catch (err) {
      if (alert) {
        alert.textContent = err.message;
        alert.classList.remove('d-none');
      }
    }
  });
});
