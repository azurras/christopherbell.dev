/**
 * Back Office access guard + admin data loader.
 * Only ADMIN users can view; others are redirected to the custom 404 page.
 */
import { API } from './lib/api.js';
import { authHeaders, fetchJson, formatWhen } from './lib/util.js';

const content = document.getElementById('backOfficeContent');
const alertBox = document.getElementById('backOfficeAlert');
const tableBody = document.querySelector('#backOfficeTable tbody');
const reportsBody = document.querySelector('#backOfficeReports tbody');

function showAlert(msg) {
  if (!alertBox) return;
  alertBox.textContent = msg;
  alertBox.classList.remove('d-none');
}

function renderRows(accounts) {
  if (!tableBody) return;
  tableBody.innerHTML = '';
  if (!accounts || accounts.length === 0) {
    const row = document.createElement('tr');
    row.innerHTML = '<td colspan="14" class="text-muted">No accounts found.</td>';
    tableBody.appendChild(row);
    return;
  }
  accounts.forEach(account => {
    const fullName = [account.firstName, account.lastName].filter(Boolean).join(' ');
    const row = document.createElement('tr');
    row.innerHTML = `
      <td>${account.username || '—'}</td>
      <td>${fullName || '—'}</td>
      <td>${account.email || '—'}</td>
      <td>${account.role || '—'}</td>
      <td>${account.status || '—'}</td>
      <td>${account.isApproved ? 'Yes' : 'No'}</td>
      <td>${account.createdOn ? formatWhen(account.createdOn) : '—'}</td>
      <td>${account.lastLoginOn ? formatWhen(account.lastLoginOn) : '—'}</td>
      <td>${account.lastUpdatedOn ? formatWhen(account.lastUpdatedOn) : '—'}</td>
      <td class="text-muted">${account.id || '—'}</td>
      <td>${account.type || '—'}</td>
      <td>${account.approvedBy || '—'}</td>
      <td>${account.createdBy || '—'}</td>
      <td>${account.lastModifiedBy || '—'}</td>
    `;
    tableBody.appendChild(row);
  });
}

function renderReports(reports) {
  if (!reportsBody) return;
  reportsBody.innerHTML = '';
  if (!reports || reports.length === 0) {
    const row = document.createElement('tr');
    row.innerHTML = '<td colspan="8" class="text-muted">No reports found.</td>';
    reportsBody.appendChild(row);
    return;
  }
  reports.forEach(report => {
    const row = document.createElement('tr');
    const status = report.status || 'OPEN';
    const resolution = report.resolution || '—';
    const resolvedOn = report.resolvedOn ? formatWhen(report.resolvedOn) : '—';
    row.innerHTML = `
      <td>${report.createdOn ? formatWhen(report.createdOn) : '—'}</td>
      <td>${report.reason || '—'}</td>
      <td>@${report.reporterUsername || '—'}</td>
      <td>@${report.reportedUsername || '—'}</td>
      <td class="text-truncate" style="max-width: 520px;">${report.postText || '—'}</td>
      <td>${status}</td>
      <td>${resolution}</td>
      <td>${resolvedOn}</td>
      <td>
        ${status === 'OPEN' ? `
        <select class="form-select form-select-sm report-action" data-report="${report.id}">
          <option value="" selected>Choose…</option>
          <option value="CLOSE_NO_ACTION">Close (no action)</option>
          <option value="DELETE_POST">Delete post</option>
          <option value="DELETE_POST_AND_SUSPEND_USER">Delete post + suspend user</option>
        </select>` : `
        <select class="form-select form-select-sm report-action" data-report="${report.id}">
          <option value="" selected>Choose…</option>
          <option value="REOPEN">Reopen</option>
        </select>`}
      </td>
    `;
    reportsBody.appendChild(row);
  });
}

async function resolveReport(reportId, resolution) {
  await fetchJson(API.reports.resolve(reportId), {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ resolution })
  });
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
    if (content) content.classList.remove('d-none');

    const accounts = await fetchJson(API.accounts.base, { headers: authHeaders() });
    renderRows(accounts || []);

    const reports = await fetchJson(API.reports.list, { headers: authHeaders() });
    renderReports(reports || []);

    reportsBody?.addEventListener('change', async (e) => {
      const target = e.target;
      if (!(target instanceof HTMLSelectElement)) return;
      if (!target.classList.contains('report-action')) return;
      const reportId = target.getAttribute('data-report');
      const resolution = target.value;
      if (!reportId || !resolution) return;
      try {
        await resolveReport(reportId, resolution);
        const refreshed = await fetchJson(API.reports.list, { headers: authHeaders() });
        renderReports(refreshed || []);
      } catch (err) {
        showAlert(err.message || 'Failed to resolve report.');
      }
    });
  } catch (err) {
    if (err?.message) {
      showAlert(err.message);
    }
    window.location.replace('/404');
  }
}

gateBackOffice();
