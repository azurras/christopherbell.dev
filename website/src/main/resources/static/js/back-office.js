/**
 * Back Office access guard + admin dashboard.
 */
import { API } from './lib/api.js';
import { canesBoxIndexResultMarkup } from './lib/back-office-canes-box-index.js';
import {
  promotedRoleForAction,
  rolePromotionOptions,
  sharedFolderPermissionState,
} from './lib/back-office-users.js';
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
const sharedFolderPermissionsTemplate = document.getElementById('sharedFolderPermissionsTemplate');
const wflOperationStatus = document.getElementById('wflOperationStatus');
const canesBoxIndexOperationStatus = document.getElementById('canesBoxIndexOperationStatus');
const canesBoxManualPriceForm = document.getElementById('canesBoxManualPriceForm');
const locationOperationStatus = document.getElementById('locationOperationStatus');
const vehicleOperationStatus = document.getElementById('vehicleOperationStatus');
const contentOperationStatus = document.getElementById('contentOperationStatus');
const vehicleVinForm = document.getElementById('vehicleVinForm');
const vehicleVinBatchForm = document.getElementById('vehicleVinBatchForm');

let accounts = [];
let reports = [];
let activities = [];
let restaurants = [];
let vehicles = [];
let blogPosts = [];

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
  renderOperationResult(wflOperationStatus, 'Restaurant counts have not been loaded yet.');
  renderOperationResult(canesBoxIndexOperationStatus, 'Raising Canes Box Index has not been pulled in this session.');
  renderOperationResult(locationOperationStatus, 'Census ZIP coordinates have not been imported in this session.');
  renderOperationResult(vehicleOperationStatus, 'Vehicle state has not been loaded yet.');
  renderOperationResult(contentOperationStatus, 'Content data has not been loaded yet.');
}

function renderState(container, message) {
  if (!container) return;
  container.innerHTML = `<div class="empty-state">${sanitize(message)}</div>`;
}

function renderOperationResult(container, content, tone = 'neutral') {
  if (!container) return;
  container.className = `operation-result operation-${tone}`;
  container.innerHTML = content;
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
            <span>${sanitize(repeatReportSummary(report))}</span>
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

function repeatReportSummary(report) {
  const open = report.openReportsForAccount ?? 0;
  const resolved = report.resolvedReportsForAccount ?? 0;
  return `${open} open / ${resolved} resolved reports for account`;
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
        <div class="queue-actions">
          ${userActionSelect(account)}
        </div>
      </article>
    `;
  }).join('');
}

function userActionSelect(account) {
  const status = (account.status || '').toUpperCase();
  const options = [];
  rolePromotionOptions(account).forEach(option => {
    options.push(`<option value="${sanitize(option.value)}">${sanitize(option.label)}</option>`);
  });
  if (!account.isApproved) {
    options.push('<option value="APPROVE">Approve</option>');
  }
  if (status !== 'SUSPENDED') {
    options.push('<option value="SUSPEND">Suspend</option>');
  }
  if (status !== 'ACTIVE' || !account.isApproved) {
    options.push('<option value="ACTIVATE">Activate</option>');
  }
  if (!options.length) {
    return '<span class="queue-age">No actions</span>';
  }
  return `
    <select class="form-select form-select-sm user-action" data-account="${sanitize(account.id || '')}" aria-label="User action">
      <option value="" selected>Action…</option>
      ${options.join('')}
    </select>
  `;
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
  if (type === 'user') {
    renderSharedFolderPermissions(item);
  }
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
      ${detailRow('Open reports for account', report.openReportsForAccount ?? 0)}
      ${detailRow('Resolved reports for account', report.resolvedReportsForAccount ?? 0)}
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
    <div id="sharedFolderPermissions"></div>
    <div class="detail-section">
      ${userActionSelect(account)}
      <button type="button" class="btn btn-outline-secondary btn-sm" data-user-posts="${sanitize(account.id || '')}">Load User Posts</button>
      <div id="drawerUserPosts" class="operation-result">Posts have not been loaded.</div>
    </div>
  `;
}

function renderSharedFolderPermissions(account) {
  const host = document.getElementById('sharedFolderPermissions');
  if (!host || !sharedFolderPermissionsTemplate) return;

  const state = sharedFolderPermissionState(account);
  const fragment = sharedFolderPermissionsTemplate.content.cloneNode(true);
  const read = fragment.querySelector('[data-shared-folder-permission="read"]');
  const write = fragment.querySelector('[data-shared-folder-permission="write"]');
  [read, write].forEach(input => {
    input.dataset.account = account.id || '';
    input.disabled = state.disabled;
  });
  read.checked = state.read;
  write.checked = state.write;
  host.replaceChildren(fragment);
}

async function resolveReport(reportId, resolution) {
  await fetchJson(API.reports.resolve(reportId), {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ resolution })
  });
}

async function updateAccount(accountId, patch) {
  return fetchJson(API.accounts.update, {
    method: 'PUT',
    headers: authHeaders(),
    body: JSON.stringify({ id: accountId, ...patch }),
  });
}

async function updateSharedFolderPermissions(accountId, { read, write }) {
  return fetchJson(API.accounts.updateSharedFolderPermissions(accountId), {
    method: 'PATCH',
    headers: authHeaders(),
    body: JSON.stringify({ read, write }),
  });
}

async function approveAccount(accountId) {
  return fetchJson(API.accounts.approve(accountId), {
    method: 'POST',
    headers: authHeaders(),
    body: '{}',
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

function wflCountsMarkup() {
  const withCoordinates = restaurants.filter(restaurant =>
    restaurant.address
    && typeof restaurant.address.latitude === 'number'
    && typeof restaurant.address.longitude === 'number').length;
  const withoutCoordinates = Math.max(0, restaurants.length - withCoordinates);
  return `
    <div class="operation-stat-grid">
      <span><strong>${restaurants.length}</strong>Total restaurants</span>
      <span><strong>${withCoordinates}</strong>With coordinates</span>
      <span><strong>${withoutCoordinates}</strong>Missing coordinates</span>
    </div>
  `;
}

function resultSummary(result, labels) {
  return `
    <div class="operation-stat-grid">
      ${labels.map(([key, label]) => `<span><strong>${sanitize(result?.[key] ?? 0)}</strong>${sanitize(label)}</span>`).join('')}
    </div>
  `;
}

async function loadWflCounts() {
  restaurants = await fetchJson(API.whatsForLunch.restaurants, { headers: authHeaders() }) || [];
  renderOperationResult(wflOperationStatus, wflCountsMarkup(), 'success');
}

async function getWflCountsMarkup() {
  restaurants = await fetchJson(API.whatsForLunch.restaurants, { headers: authHeaders() }) || [];
  return wflCountsMarkup();
}

async function importRestaurants(button) {
  button.disabled = true;
  renderOperationResult(wflOperationStatus, 'Importing restaurants from OpenStreetMap…');
  try {
    const result = await fetchJson(API.whatsForLunch.importOpenStreetMap, {
      method: 'POST',
      headers: authHeaders(),
    });
    renderOperationResult(wflOperationStatus, `
      <p class="operation-message">Import complete.</p>
      ${resultSummary(result, [
        ['fetched', 'Fetched'],
        ['imported', 'Imported'],
        ['updated', 'Updated'],
        ['skippedExisting', 'Skipped existing'],
        ['skippedInvalid', 'Skipped invalid'],
      ])}
      ${await getWflCountsMarkup()}
    `, 'success');
  } finally {
    button.disabled = false;
  }
}

async function dedupeRestaurants(button) {
  button.disabled = true;
  renderOperationResult(wflOperationStatus, 'Removing duplicate restaurant names…');
  try {
    const result = await fetchJson(API.whatsForLunch.dedupeNames, {
      method: 'POST',
      headers: authHeaders(),
    });
    renderOperationResult(wflOperationStatus, `
      <p class="operation-message">Duplicate cleanup complete.</p>
      ${resultSummary(result, [
        ['duplicateGroups', 'Duplicate groups'],
        ['deleted', 'Deleted'],
        ['updatedSurvivors', 'Updated survivors'],
      ])}
      ${await getWflCountsMarkup()}
    `, 'success');
  } finally {
    button.disabled = false;
  }
}

async function collectCanesBoxIndex(button) {
  button.disabled = true;
  renderOperationResult(canesBoxIndexOperationStatus, 'Pulling a new Raising Canes Box Index data point…');
  try {
    const result = await fetchJson(API.canesBoxTracker.collect, {
      method: 'POST',
      headers: authHeaders(),
    });
    renderOperationResult(canesBoxIndexOperationStatus, canesBoxIndexResultMarkup(result), 'success');
  } finally {
    button.disabled = false;
  }
}

async function reviewCanesBoxMetro(button) {
  const action = button.getAttribute('data-canes-box-review');
  const weekStartDate = button.getAttribute('data-week-start-date');
  const metroName = button.getAttribute('data-metro-name');
  if (!action || !weekStartDate || !metroName) return;
  button.disabled = true;
  const note = window.prompt(`${action === 'approve' ? 'Approve' : 'Reject'} ${metroName}: review note`) || '';
  try {
    const url = action === 'approve'
      ? API.canesBoxTracker.approveMetro(weekStartDate, metroName)
      : API.canesBoxTracker.rejectMetro(weekStartDate, metroName);
    const result = await fetchJson(url, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ note }),
    });
    renderOperationResult(canesBoxIndexOperationStatus, canesBoxIndexResultMarkup(result), 'success');
  } finally {
    button.disabled = false;
  }
}

async function saveManualCanesBoxPrice(form) {
  const data = new FormData(form);
  const payload = {
    metroName: String(data.get('metroName') || '').trim(),
    price: Number(data.get('price')),
    sourceUrl: String(data.get('sourceUrl') || '').trim(),
    note: String(data.get('note') || '').trim(),
  };
  if (!payload.metroName || !payload.price || !payload.sourceUrl) {
    showAlert('Metro, price, and evidence URL are required.');
    return;
  }
  const result = await fetchJson(API.canesBoxTracker.manualPrice, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(payload),
  });
  form.reset();
  renderOperationResult(canesBoxIndexOperationStatus, canesBoxIndexResultMarkup(result), 'success');
}

async function importZipCoordinates(button) {
  button.disabled = true;
  renderOperationResult(locationOperationStatus, 'Importing Census ZIP coordinates…');
  try {
    const result = await fetchJson(API.location.importCensusZipCoordinates, {
      method: 'POST',
      headers: authHeaders(),
    });
    renderOperationResult(locationOperationStatus, `
      <p class="operation-message">ZIP coordinate import complete.</p>
      <p class="operation-message">${sanitize(result.source || 'Source')} ${sanitize(result.sourceYear || '')}</p>
      ${resultSummary(result, [
        ['processed', 'Processed'],
        ['created', 'Created'],
        ['updated', 'Updated'],
        ['unchanged', 'Unchanged'],
        ['deleted', 'Deleted'],
      ])}
    `, 'success');
  } finally {
    button.disabled = false;
  }
}

function vehicleStateMarkup(state) {
  return `<pre class="operation-pre">${sanitize(JSON.stringify(state || {}, null, 2))}</pre>`;
}

async function loadVehicleState() {
  const state = await fetchJson(API.vehicles.dataCollectionState, { headers: authHeaders() });
  renderOperationResult(vehicleOperationStatus, vehicleStateMarkup(state), 'success');
}

async function loadVehicleCount() {
  vehicles = await fetchJson(API.vehicles.base, { headers: authHeaders() }) || [];
  renderOperationResult(vehicleOperationStatus, `
    <div class="operation-stat-grid">
      <span><strong>${vehicles.length}</strong>Stored vehicles</span>
    </div>
  `, 'success');
}

async function createVehicleFromVin(vin) {
  const vehicle = await fetchJson(API.vehicles.createFromVin, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ vin }),
  });
  renderOperationResult(vehicleOperationStatus, `
    <p class="operation-message">Vehicle created.</p>
    ${detailRow('VIN', vehicle.vin)}
    ${detailRow('Vehicle', [vehicle.year, vehicle.make, vehicle.model].filter(Boolean).join(' '))}
    ${detailRow('ID', vehicle.id)}
  `, 'success');
}

async function createVehiclesFromVins(vins) {
  const created = await fetchJson(API.vehicles.createFromVins, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ vins }),
  });
  renderOperationResult(vehicleOperationStatus, `
    <p class="operation-message">Batch import complete.</p>
    <div class="operation-stat-grid">
      <span><strong>${created?.length || 0}</strong>Created vehicles</span>
    </div>
  `, 'success');
}

async function loadBlogPosts() {
  const response = await fetchJson(API.blog.posts, { headers: authHeaders() });
  blogPosts = response?.posts || [];
  const postList = blogPosts.slice(0, 5).map(post => `
    <li>
      <strong>${sanitize(post.title || post.id || 'Untitled')}</strong>
      <span>${sanitize(post.id || '')}</span>
    </li>
  `).join('');
  renderOperationResult(contentOperationStatus, `
    <div class="operation-stat-grid">
      <span><strong>${blogPosts.length}</strong>Blog posts</span>
    </div>
    ${postList ? `<ul class="operation-list">${postList}</ul>` : ''}
  `, 'success');
}

async function loadUserPosts(accountId) {
  const target = document.getElementById('drawerUserPosts');
  if (!target) return;
  renderOperationResult(target, 'Loading posts…');
  const posts = await fetchJson(API.posts.byAccount(accountId), { headers: authHeaders() }) || [];
  const postList = posts.slice(0, 8).map(post => `
    <li>
      <strong>${sanitize(post.content || post.text || post.id || 'Post')}</strong>
      <span>${post.createdOn ? sanitize(formatWhen(post.createdOn)) : sanitize(post.id || '')}</span>
    </li>
  `).join('');
  renderOperationResult(target, `
    <div class="operation-stat-grid">
      <span><strong>${posts.length}</strong>User posts</span>
    </div>
    ${postList ? `<ul class="operation-list">${postList}</ul>` : ''}
  `, 'success');
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

async function handleUserAction(target) {
  const accountId = target.getAttribute('data-account');
  const action = target.value;
  if (!accountId || !action) return;

  target.disabled = true;
  try {
    if (action === 'APPROVE') {
      await approveAccount(accountId);
    } else if (action === 'SUSPEND') {
      await updateAccount(accountId, { status: 'SUSPENDED' });
    } else if (action === 'ACTIVATE') {
      await updateAccount(accountId, { status: 'ACTIVE', isApproved: true });
    } else {
      const role = promotedRoleForAction(action);
      if (role) {
        await updateAccount(accountId, { role });
      }
    }
    await refreshDashboard();
    closeDrawer();
  } catch (err) {
    showAlert(err.message || 'Failed to update user.');
  } finally {
    target.disabled = false;
  }
}

async function handleSharedFolderPermissionChange(target) {
  const accountId = target.dataset.account;
  const permission = target.dataset.sharedFolderPermission;
  const account = accounts.find(candidate => candidate.id === accountId);
  if (!account || !permission) return;

  const state = sharedFolderPermissionState(account, { [permission]: target.checked });
  const controls = drawerBody?.querySelectorAll('[data-shared-folder-permission]') || [];
  controls.forEach(control => {
    control.disabled = true;
  });
  try {
    await updateSharedFolderPermissions(accountId, state);
    await refreshDashboard();
    openDrawer('user', accountId);
  } catch (err) {
    showAlert(err.message || 'Failed to update shared-folder permissions.');
    renderSharedFolderPermissions(account);
  }
}

async function handleOperation(button) {
  const operation = button.getAttribute('data-operation');
  clearAlert();
  try {
    if (operation === 'wfl-import') {
      await importRestaurants(button);
    } else if (operation === 'wfl-dedupe') {
      await dedupeRestaurants(button);
    } else if (operation === 'wfl-load') {
      button.disabled = true;
      await loadWflCounts();
    } else if (operation === 'canes-box-index-collect') {
      await collectCanesBoxIndex(button);
    } else if (operation === 'location-zip-import') {
      await importZipCoordinates(button);
    } else if (operation === 'vehicle-state') {
      button.disabled = true;
      await loadVehicleState();
    } else if (operation === 'vehicle-load') {
      button.disabled = true;
      await loadVehicleCount();
    } else if (operation === 'blog-load') {
      button.disabled = true;
      await loadBlogPosts();
    }
  } catch (err) {
    showAlert(err.message || 'Operation failed.');
  } finally {
    button.disabled = false;
  }
}

function wireEvents() {
  document.addEventListener('click', (event) => {
    const action = event.target;
    const operationButton = action.closest?.('[data-operation]');
    if (operationButton instanceof HTMLButtonElement) {
      handleOperation(operationButton);
      return;
    }

    const canesReviewButton = action.closest?.('[data-canes-box-review]');
    if (canesReviewButton instanceof HTMLButtonElement) {
      reviewCanesBoxMetro(canesReviewButton).catch(err => showAlert(err.message || 'Failed to review price.'));
      return;
    }

    const userPostsButton = action.closest?.('[data-user-posts]');
    if (userPostsButton instanceof HTMLButtonElement) {
      loadUserPosts(userPostsButton.getAttribute('data-user-posts'));
      return;
    }

    if (action.closest?.('.queue-actions')) {
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
    if (target instanceof HTMLInputElement && target.dataset.sharedFolderPermission) {
      await handleSharedFolderPermissionChange(target);
    } else if (target instanceof HTMLSelectElement && target.classList.contains('report-action')) {
      await handleReportAction(target);
    } else if (target instanceof HTMLSelectElement && target.classList.contains('user-action')) {
      await handleUserAction(target);
    }
  });

  vehicleVinForm?.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearAlert();
    const input = vehicleVinForm.querySelector('[name="vin"]');
    const vin = input?.value?.trim();
    if (!vin) return;
    try {
      await createVehicleFromVin(vin);
      vehicleVinForm.reset();
    } catch (err) {
      showAlert(err.message || 'Failed to create vehicle.');
    }
  });

  canesBoxManualPriceForm?.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearAlert();
    try {
      await saveManualCanesBoxPrice(canesBoxManualPriceForm);
    } catch (err) {
      showAlert(err.message || 'Failed to save manual price.');
    }
  });

  vehicleVinBatchForm?.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearAlert();
    const input = vehicleVinBatchForm.querySelector('[name="vins"]');
    const vins = (input?.value || '')
        .split(/\s+/)
        .map(value => value.trim())
        .filter(Boolean);
    if (!vins.length) return;
    try {
      await createVehiclesFromVins(vins);
      vehicleVinBatchForm.reset();
    } catch (err) {
      showAlert(err.message || 'Failed to create vehicle batch.');
    }
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
