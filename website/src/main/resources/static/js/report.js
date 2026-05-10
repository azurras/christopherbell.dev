/**
 * Report page behavior.
 * Requires authentication to submit reports.
 */
import { API } from './lib/api.js';
import { authHeaders, fetchJson, sanitize } from './lib/util.js';

const alertBox = document.getElementById('reportAlert');
const postTextEl = document.getElementById('reportPostText');
const postAuthorEl = document.getElementById('reportPostAuthor');
const form = document.getElementById('reportForm');

function showAlert(message) {
  if (!alertBox) return;
  alertBox.textContent = message;
  alertBox.classList.remove('d-none');
}

function getPostId() {
  const params = new URLSearchParams(window.location.search);
  return params.get('postId');
}

async function loadPost(postId) {
  const post = await fetchJson(API.posts.byId(postId), { headers: authHeaders() });
  if (postTextEl) postTextEl.textContent = sanitize(post.text || '');
  if (postAuthorEl) postAuthorEl.textContent = post.username ? `@${sanitize(post.username)}` : '—';
}

document.addEventListener('DOMContentLoaded', async () => {
  const token = localStorage.getItem('cbellLoginToken');
  if (!token) {
    window.location.replace('/login');
    return;
  }
  const postId = getPostId();
  if (!postId) {
    showAlert('Missing post id.');
    return;
  }
  try {
    await loadPost(postId);
  } catch (err) {
    showAlert(err.message || 'Unable to load post.');
  }
});

form?.addEventListener('submit', async (e) => {
  e.preventDefault();
  if (alertBox) alertBox.classList.add('d-none');
  const postId = getPostId();
  const reason = document.getElementById('reportReason')?.value;
  const details = document.getElementById('reportDetails')?.value?.trim() || null;
  if (!postId || !reason) {
    showAlert('Please select a reason.');
    return;
  }
  try {
    await fetchJson(API.reports.create, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ postId, reason, details })
    });
    window.location.replace('/void');
  } catch (err) {
    showAlert(err.message || 'Failed to submit report.');
  }
});
