/**
 * Report page behavior.
 * Requires authentication to submit reports.
 */
import { API } from './lib/api.js';
import { appendTextWithMentionLinks, authHeaders, fetchJson, isLoggedIn, loginRedirectUrl } from './lib/util.js';

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
  appendTextWithMentionLinks(postTextEl, post.text || '');
  if (postAuthorEl) {
    if (post.username) {
      appendTextWithMentionLinks(postAuthorEl, `@${post.username}`);
    } else {
      postAuthorEl.textContent = '-';
    }
  }
}

document.addEventListener('DOMContentLoaded', async () => {
  if (!isLoggedIn()) {
    window.location.replace(loginRedirectUrl());
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
      redirectOnUnauthorized: true,
      body: JSON.stringify({ postId, reason, details })
    });
    window.location.replace('/void');
  } catch (err) {
    showAlert(err.message || 'Failed to submit report.');
  }
});
