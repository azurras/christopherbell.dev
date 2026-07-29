/**
 * Signup page behavior.
 *
 * Submits new account details to the API and redirects to login on success.
 * Redirects authenticated users away.
 */
import { API } from '../lib/api.js';
import { fetchJson, hasAuthenticatedSession, safeRedirectTarget } from '../lib/util.js';
const alertBox = () => document.getElementById('signupAlert');

function redirectTarget() {
  const target = new URLSearchParams(window.location.search).get('redirect') || '/';
  return safeRedirectTarget(target);
}

/**
 * Create a new account via API.
 * @param {{email:string,username:string,firstName?:string,lastName?:string,password:string,federatePublicVoidPosts:boolean}} payload
 * @returns {Promise<object>} created account detail
 */
async function signup(payload) {
  return fetchJson(API.accounts.create, {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

/** Normalize required signup fields before the request crosses the API boundary. */
export function signupPayload(fields) {
  const firstName = String(fields.firstName || '').trim();
  const lastName = String(fields.lastName || '').trim();
  if (!firstName) throw new Error('First name is required.');
  if (!lastName) throw new Error('Last name is required.');
  return {
    email: String(fields.email || '').trim(),
    username: String(fields.username || '').trim(),
    firstName,
    lastName,
    password: String(fields.password || ''),
    federatePublicVoidPosts: Boolean(
      fields.federationControl?.checked && !fields.federationControl?.disabled
    ),
  };
}

/** Wire form submit and redirect rules once DOM is ready. */
document.addEventListener('DOMContentLoaded', async () => {
  if (await hasAuthenticatedSession(API.accounts.me)) {
    window.location.href = redirectTarget();
    return;
  }
  const form = document.getElementById('signupForm');
  if (!form) return;

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const fields = {
      email: document.getElementById('email')?.value?.trim(),
      username: document.getElementById('username')?.value?.trim(),
      firstName: document.getElementById('firstName')?.value,
      lastName: document.getElementById('lastName')?.value,
      password: document.getElementById('password')?.value || '',
      federationControl: document.getElementById('federatePublicVoidPosts'),
    };
    const alert = alertBox();
    alert?.classList.add('d-none');
    try {
      await signup(signupPayload(fields));
      window.location.href = `/login?redirect=${encodeURIComponent(redirectTarget())}`;
    } catch (err) {
      if (alert) {
        alert.textContent = err.message;
        alert.classList.remove('d-none');
      }
    }
  });
});
