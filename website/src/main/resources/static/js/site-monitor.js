import { API } from './lib/api.js';
import { fetchJson, isLoggedIn, sanitize } from './lib/util.js';

/** Split configured paths without silently truncating a customer's selected coverage. */
export function monitorPagePaths(pathsText) {
  const pagePaths = String(pathsText || '').split(/\r?\n/).map(path => path.trim()).filter(Boolean);
  if (!pagePaths.length || pagePaths.length > 5) throw new Error('Choose one to five page paths.');
  return pagePaths;
}

/** Safe HTML rendering of private server-owned observations; remote strings are always escaped. */
export function monitorSiteHtml(site) {
  const latestReport = site.reports?.[0];
  const formattedDate = isoDate => isoDate ? new Date(isoDate).toLocaleString() : 'Not yet';
  const findings = latestReport?.findings || [];
  const reportLinks = (site.reports || []).map(report => `<li><a href="${API.siteMonitor.report(site.id, report.id)}">${sanitize(formattedDate(report.checkedOn))} — ${sanitize(report.status)}</a></li>`).join('');
  const reportEvidence = findings.map(finding => `<li><strong>${sanitize(finding.severity)}</strong> ${sanitize(finding.path)} — ${sanitize(finding.field)}: ${sanitize(finding.before)} → ${sanitize(finding.after)}</li>`).join('');
  return `<section class="zip-coordinate-panel"><h3>${sanitize(site.label)}</h3>
    <p>${sanitize(site.origin)} · ${site.paths.map(sanitize).join(', ')}</p>
    ${site.demo ? '<p>Demonstration site</p>' : `<p>Publish this exact token at <code>${sanitize(site.origin)}/.well-known/christopherbell-site-monitor.txt</code>:</p><pre>${sanitize(site.token)}</pre><p>Ownership is checked before every capture or comparison.</p>`}
    <p>Baseline: ${sanitize(formattedDate(site.baselineOn))} · Last attempt: ${sanitize(formattedDate(site.lastAttempt))}</p>
    <p>Latest result: <strong>${sanitize(latestReport?.status || 'No checks yet')}</strong></p>
    <button type="button" class="btn btn-secondary" data-monitor-action="baseline" data-site="${sanitize(site.id)}">${site.baselineOn ? 'Replace baseline' : 'Verify and capture baseline'}</button>
    <button type="button" class="btn btn-primary" data-monitor-action="check" data-site="${sanitize(site.id)}" ${site.baselineOn ? '' : 'disabled'}>Check now</button>
    <button type="button" class="btn btn-secondary" data-monitor-action="delete" data-site="${sanitize(site.id)}">Remove website</button>
    <ul>${reportEvidence}</ul><h4>Latest reports</h4><ul>${reportLinks || '<li>No reports yet.</li>'}</ul>
    <p>Daily checks begin after a healthy baseline is captured. Removing a website deletes its saved baseline and reports.</p>
  </section>`;
}

if (typeof document !== 'undefined') {
  const statusMessage = document.getElementById('monitorMessage');
  const workspacePanel = document.getElementById('monitorWorkspace');
  const signedOutPanel = document.getElementById('monitorSignedOut');
  const sitesList = document.getElementById('monitorSites');
  let requestInFlight = false;
  const renderSites = workspace => { sitesList.innerHTML = workspace.sites.map(monitorSiteHtml).join(''); };
  /** Runs one workspace request at a time, disabling buttons and restoring their prior state. */
  async function runWorkspaceRequest(sendRequest, pendingMessage) {
    if (requestInFlight) return;
    requestInFlight = true;
    const buttonStates = Array.from(workspacePanel.querySelectorAll('button'), button => [button, button.disabled]);
    buttonStates.forEach(([button]) => { button.disabled = true; });
    statusMessage.textContent = pendingMessage;
    try {
      renderSites(await sendRequest());
      statusMessage.textContent = 'Workspace updated. Review the result and coverage before accepting changes.';
    } catch (failure) {
      statusMessage.textContent = failure.message || 'The check could not complete. Please retry.';
      if (failure.status === 401 || failure.status === 403) {
        workspacePanel.hidden = true;
        signedOutPanel.hidden = false;
      }
    } finally {
      requestInFlight = false;
      buttonStates.forEach(([button, wasDisabled]) => { if (button.isConnected) button.disabled = wasDisabled; });
    }
  }
  document.getElementById('monitorCreate')?.addEventListener('submit', event => {
    event.preventDefault();
    void runWorkspaceRequest(async () => {
      const workspace = await fetchJson(API.siteMonitor.sites, { method: 'POST', body: JSON.stringify({
        label: document.getElementById('monitorLabel').value,
        origin: document.getElementById('monitorOrigin').value,
        paths: monitorPagePaths(document.getElementById('monitorPaths').value), demo: false,
      }) });
      event.target.reset();
      document.getElementById('monitorPaths').value = '/';
      return workspace;
    }, 'Adding website…');
  });
  document.getElementById('monitorDemo')?.addEventListener('click', () => {
    void runWorkspaceRequest(() => fetchJson(API.siteMonitor.sites, { method: 'POST',
      body: JSON.stringify({ label: 'Demonstration website', demo: true }) }), 'Adding demonstration…');
  });
  sitesList?.addEventListener('click', event => {
    const button = event.target.closest('button[data-monitor-action]');
    if (!button || requestInFlight) return;
    const { monitorAction: action, site: siteId } = button.dataset;
    if (action === 'delete' && !window.confirm('Remove this website and all its reports?')) return;
    if (action === 'baseline' && !window.confirm('Accept a new baseline only if these pages represent the intended website. Continue?')) return;
    void runWorkspaceRequest(() => fetchJson(action === 'delete' ? API.siteMonitor.site(siteId)
      : API.siteMonitor.action(siteId, action), { method: action === 'delete' ? 'DELETE' : 'POST' }),
    action === 'delete' ? 'Removing website…' : 'Checking configured pages. This can take up to 45 seconds…');
  });
  if (isLoggedIn()) {
    workspacePanel.hidden = false;
    signedOutPanel.hidden = true;
    void runWorkspaceRequest(() => fetchJson(API.siteMonitor.workspace), 'Loading your workspace…');
  }
}
