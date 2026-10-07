/**
 * Report page behavior.
 * Requires authentication to submit reports.
 */
import { API } from './lib/api.js';
import { renderAlert } from './lib/status-message.js';
import { appendTextWithMentionLinks, authHeaders, fetchJson, isLoggedIn, loginRedirectUrl } from './lib/util.js';

const alertBox = document.getElementById('reportAlert');
const postTextElement = document.getElementById('reportPostText');
const postAuthorElement = document.getElementById('reportPostAuthor');
const reportForm = document.getElementById('reportForm');

function showAlert(message) {
  renderAlert(alertBox, message);
}

function reportedPostIdFromUrl() {
  const queryParameters = new URLSearchParams(window.location.search);
  return queryParameters.get('postId');
}

async function showReportedPost(postId) {
  const reportedPost = await fetchJson(API.posts.byId(postId), { headers: authHeaders() });
  appendTextWithMentionLinks(postTextElement, reportedPost.text || '');
  if (postAuthorElement) {
    if (reportedPost.username) {
      appendTextWithMentionLinks(postAuthorElement, `@${reportedPost.username}`);
    } else {
      postAuthorElement.textContent = '-';
    }
  }
}

document.addEventListener('DOMContentLoaded', async () => {
  if (!isLoggedIn()) {
    window.location.replace(loginRedirectUrl());
    return;
  }
  const postId = reportedPostIdFromUrl();
  if (!postId) {
    showAlert('Missing post id.');
    return;
  }
  try {
    await showReportedPost(postId);
  } catch (loadFailure) {
    showAlert(loadFailure.message || 'Unable to load post.');
  }
});

reportForm?.addEventListener('submit', async (submitEvent) => {
  submitEvent.preventDefault();
  if (alertBox) alertBox.classList.add('d-none');
  const postId = reportedPostIdFromUrl();
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
  } catch (submitFailure) {
    showAlert(submitFailure.message || 'Failed to submit report.');
  }
});
