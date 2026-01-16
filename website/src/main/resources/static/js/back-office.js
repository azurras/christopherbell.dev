/**
 * Back Office access guard + admin data loader.
 * Only ADMIN users can view; others are redirected to the custom 404 page.
 */
import { API } from './lib/api.js';
import { authHeaders, fetchJson, formatWhen } from './lib/util.js';

const content = document.getElementById('backOfficeContent');
const alertBox = document.getElementById('backOfficeAlert');
const tableBody = document.querySelector('#backOfficeTable tbody');

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
  } catch (err) {
    if (err?.message) {
      showAlert(err.message);
    }
    window.location.replace('/404');
  }
}

gateBackOffice();
