/**
 * Back Office access guard + admin dashboard.
 */
import { API } from './lib/api.js';
import { authHeaders, fetchJson, formatWhen, sanitize } from './lib/util.js';

const content = document.getElementById('backOfficeContent');
const alertBox = document.getElementById('backOfficeAlert');
const reportQueue = document.getElementById('reportQueue');
const userQueue = document.getElementById('userQueue');
const activityList = document.getElementById('activityList');
const drawer = document.getElementById('backOfficeDrawer');
const drawerBody = document.getElementById('drawerBody');
const drawerClose = document.getElementById('drawerClose');
const drawerKicker = document.getElementById('drawerKicker');
const drawerTitle = document.getElementById('drawerTitle');

let accounts = [];
let reports = [];
let activities = [];

function showAlert(msg) {
  if (!alertBox) return;
  alertBox.textContent = msg;
  alertBox.classList.remove('d-none');
}

function clearAlert() {
  alertBox?.classList.add('d-none');
}

function setLoading() {
  renderState(reportQueue, 'Loading reports…');
  renderState(userQueue, 'Loading users…');
  renderState(activityList, 'Loading activity…');
}

function renderState(container, message) {
  if (!container) return;
  container.innerHTML = `<div class="empty-state">${sanitize(message)}</div>`;
}

function statusClass(status) {
  const value = (status || '').toLowerCase();
  if (value === 'open') return 'status-open';
  if (value === 'resolved' || value === 'active') return 'status-resolved';
  if (value === 'suspended') return 'status-suspended';
  if (value === 'inactive') return 'status-pending';
  return 'status-neutral';
}

function reportSeverityClass(report) {
  if ((report.status || 'OPEN') === 'RESOLVED') return 'queue-resolved';
  const reason = (report.reason || '').toLowerCase();
  if (['harassment', 'violence', 'sexual'].includes(reason)) return 'queue-severe';
  return 'queue-open';
}

function userSeverityClass(account) {
  if ((account.status || '').toUpperCase() === 'SUSPENDED') return 'queue-suspended';
  if (!account.isApproved) return 'queue-pending';
  return 'queue-resolved';
}

function relativeAge(value) {
  if (!value) return '—';
  const seconds = Math.max(1, Math.floor((Date.now() - new Date(value).getTime()) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

function fullName(account) {
  return [account.firstName, account.lastName].filter(Boolean).join(' ');
}

function renderMetrics() {
  const totalReports = reports.length;
  const openReports = reports.filter(report => (report.status || 'OPEN') === 'OPEN').length;
  const pendingUsers = accounts.filter(account => !account.isApproved).length;
  const suspendedUsers = accounts.filter(account => (account.status || '').toUpperCase() === 'SUSPENDED').length;
  const metrics = {
    metricTotalReports: totalReports,
    metricOpenReports: openReports,
    metricPendingUsers: pendingUsers,
    metricSuspendedUsers: suspendedUsers,
    metricRecentActivity: activities.length,
    reportQueueCount: `${totalReports} total`,
    userQueueCount: `${accounts.length} total`,
  };

  Object.entries(metrics).forEach(([id, value]) => {
    const el = document.getElementById(id);
    if (el) el.textContent = String(value);
  });
}

function renderReports() {
  if (!reportQueue) return;
  if (!reports.length) {
    renderState(reportQueue, 'No reports yet. The queue is clear.');
    return;
  }

  reportQueue.innerHTML = reports.map(report => {
    const status = report.status || 'OPEN';
    const reason = report.reason || 'other';
    const postText = sanitize(report.postText || 'No post text available.');
    return `
      <article class="queue-card ${reportSeverityClass(report)}" data-detail-type="report" data-id="${sanitize(report.id || '')}" tabindex="0">
        <div class="queue-main">
          <div class="queue-topline">
            <span class="status-pill ${statusClass(status)}">${sanitize(status)}</span>
            <span class="queue-age">${relativeAge(report.createdOn)}</span>
          </div>
          <h3>${sanitize(reason)}</h3>
          <p>${postText}</p>
          <div class="queue-meta">
            <span>Reporter @${sanitize(report.reporterUsername || 'unknown')}</span>
            <span>Reported @${sanitize(report.reportedUsername || 'unknown')}</span>
          </div>
        </div>
        <div class="queue-actions">
          ${reportActionSelect(report)}
        </div>
      </article>
    `;
  }).join('');
}

function reportActionSelect(report) {
  const status = report.status || 'OPEN';
  const options = status === 'OPEN'
      ? `
        <option value="CLOSE_NO_ACTION">Close</option>
        <option value="DELETE_POST">Delete post</option>
        <option value="DELETE_POST_AND_SUSPEND_USER">Delete + suspend</option>
      `
      : '<option value="REOPEN">Reopen</option>';

  return `
    <select class="form-select form-select-sm report-action" data-report="${sanitize(report.id || '')}" aria-label="Report action">
      <option value="" selected>Action…</option>
      ${options}
    </select>
  `;
}

function renderUsers() {
  if (!userQueue) return;
  if (!accounts.length) {
    renderState(userQueue, 'No accounts found.');
    return;
  }

  userQueue.innerHTML = accounts.map(account => {
    const status = account.status || 'UNKNOWN';
    const name = fullName(account);
    return `
      <article class="queue-card ${userSeverityClass(account)}" data-detail-type="user" data-id="${sanitize(account.id || '')}" tabindex="0">
        <div class="queue-main">
          <div class="queue-topline">
            <span class="status-pill ${statusClass(status)}">${sanitize(status)}</span>
            <span class="queue-age">${account.createdOn ? `Joined ${relativeAge(account.createdOn)}` : 'No creation date'}</span>
          </div>
          <h3>@${sanitize(account.username || 'unknown')}</h3>
          <p>${sanitize(name || account.email || 'No profile details')}</p>
          <div class="queue-meta">
            <span>${account.isApproved ? 'Approved' : 'Pending approval'}</span>
            <span>${sanitize(account.role || 'USER')}</span>
          </div>
        </div>
      </article>
    `;
  }).join('');
}

function renderActivity() {
  if (!activityList) return;
  if (!activities.length) {
    renderState(activityList, 'No admin activity recorded yet.');
    return;
  }

  activityList.innerHTML = activities.map(activity => `
    <article class="activity-item">
      <div class="activity-dot ${activityClass(activity.action)}"></div>
      <div>
        <strong>${sanitize(activity.message || activity.action || 'Activity')}</strong>
        <span>${activity.createdOn ? formatWhen(activity.createdOn) : '—'}</span>
      </div>
    </article>
  `).join('');
}

function activityClass(action) {
  if (action === 'USER_SUSPENDED') return 'activity-danger';
  if (action === 'POST_DELETED') return 'activity-warning';
  if (action === 'REPORT_RESOLVED') return 'activity-success';
  return 'activity-neutral';
}

function openDrawer(type, id) {
  const item = type === 'report'
      ? reports.find(report => report.id === id)
      : accounts.find(account => account.id === id);
  if (!item || !drawer || !drawerBody || !drawerTitle || !drawerKicker) return;

  drawerKicker.textContent = type === 'report' ? 'Report Details' : 'User Details';
  drawerTitle.textContent = type === 'report'
      ? `${item.reason || 'Report'}`
      : `@${item.username || 'user'}`;
  drawerBody.innerHTML = type === 'report' ? reportDetails(item) : userDetails(item);
  drawer.classList.remove('d-none');
  drawer.setAttribute('aria-hidden', 'false');
}

function closeDrawer() {
  drawer?.classList.add('d-none');
  drawer?.setAttribute('aria-hidden', 'true');
}

function detailRow(label, value) {
  return `
    <div class="detail-row">
      <span>${sanitize(label)}</span>
      <strong>${sanitize(value || '—')}</strong>
    </div>
  `;
}

function reportDetails(report) {
  return `
    <div class="detail-section">
      ${detailRow('Status', report.status || 'OPEN')}
      ${detailRow('Reason', report.reason)}
      ${detailRow('Reporter', `@${report.reporterUsername || 'unknown'}`)}
      ${detailRow('Reported', `@${report.reportedUsername || 'unknown'}`)}
      ${detailRow('Created', report.createdOn ? formatWhen(report.createdOn) : '—')}
      ${detailRow('Resolved', report.resolvedOn ? formatWhen(report.resolvedOn) : '—')}
      ${detailRow('Resolution', report.resolution)}
    </div>
    <div class="detail-section">
      <span class="detail-label">Post</span>
      <p class="detail-copy">${sanitize(report.postText || 'No post text available.')}</p>
    </div>
    <div class="detail-section">
      <span class="detail-label">Details</span>
      <p class="detail-copy">${sanitize(report.details || 'No additional details.')}</p>
    </div>
    <div class="detail-section">
      ${reportActionSelect(report)}
    </div>
  `;
}

function userDetails(account) {
  return `
    <div class="detail-section">
      ${detailRow('Status', account.status)}
      ${detailRow('Approved', account.isApproved ? 'Yes' : 'No')}
      ${detailRow('Role', account.role)}
      ${detailRow('Email', account.email)}
      ${detailRow('Name', fullName(account))}
      ${detailRow('Created', account.createdOn ? formatWhen(account.createdOn) : '—')}
      ${detailRow('Last login', account.lastLoginOn ? formatWhen(account.lastLoginOn) : '—')}
      ${detailRow('Updated', account.lastUpdatedOn ? formatWhen(account.lastUpdatedOn) : '—')}
      ${detailRow('ID', account.id)}
    </div>
  `;
}

async function resolveReport(reportId, resolution) {
  await fetchJson(API.reports.resolve(reportId), {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ resolution })
  });
}

async function refreshDashboard() {
  clearAlert();
  [accounts, reports, activities] = await Promise.all([
    fetchJson(API.accounts.base, { headers: authHeaders() }),
    fetchJson(API.reports.list, { headers: authHeaders() }),
    fetchJson(API.admin.activity, { headers: authHeaders() }),
  ]);
  accounts = accounts || [];
  reports = reports || [];
  activities = activities || [];
  renderMetrics();
  renderReports();
  renderUsers();
  renderActivity();
}

async function handleReportAction(target) {
  const reportId = target.getAttribute('data-report');
  const resolution = target.value;
  if (!reportId || !resolution) return;

  target.disabled = true;
  try {
    await resolveReport(reportId, resolution);
    await refreshDashboard();
    closeDrawer();
  } catch (err) {
    showAlert(err.message || 'Failed to resolve report.');
  } finally {
    target.disabled = false;
  }
}

function wireEvents() {
  document.addEventListener('click', (event) => {
    const action = event.target;
    if (action instanceof HTMLSelectElement && action.classList.contains('report-action')) {
      return;
    }

    const card = event.target.closest?.('.queue-card');
    if (!card) return;
    openDrawer(card.getAttribute('data-detail-type'), card.getAttribute('data-id'));
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeDrawer();
    if (event.key !== 'Enter') return;
    const card = event.target.closest?.('.queue-card');
    if (!card) return;
    openDrawer(card.getAttribute('data-detail-type'), card.getAttribute('data-id'));
  });

  document.addEventListener('change', async (event) => {
    const target = event.target;
    if (!(target instanceof HTMLSelectElement)) return;
    if (!target.classList.contains('report-action')) return;
    await handleReportAction(target);
  });

  drawerClose?.addEventListener('click', closeDrawer);
}

async function gateBackOffice() {
  const token = localStorage.getItem('cbellLoginToken');
  if (!token) {
    window.location.replace('/404');
    return;
  }

  try {
    const resp = await fetch(API.accounts.me, {
      headers: { Authorization: `Bearer ${token}` }
    });
    const data = await resp.json().catch(() => ({}));
    const role = data?.payload?.role || '';
    if (!resp.ok || role !== 'ADMIN') {
      window.location.replace('/404');
      return;
    }

    localStorage.setItem('cbellRole', role);
    content?.classList.remove('d-none');
    setLoading();
    wireEvents();
    await refreshDashboard();
  } catch (err) {
    if (err?.message) {
      showAlert(err.message);
    }
    window.location.replace('/404');
  }
}

gateBackOffice();
