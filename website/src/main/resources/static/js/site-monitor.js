import { API } from './lib/api.js';
import { fetchJson, isLoggedIn, sanitize } from './lib/util.js';

/** Split configured paths without silently truncating a customer's selected coverage. */
export function monitorPagePaths(value) {
  const paths = String(value || '').split(/\r?\n/).map(path => path.trim()).filter(Boolean);
  if (!paths.length || paths.length > 5) throw new Error('Choose one to five page paths.');
  return paths;
}

/** Safe HTML rendering of private server-owned observations; remote strings are always escaped. */
export function monitorSiteHtml(site) {
  const latest = site.reports?.[0];
  const date = value => value ? new Date(value).toLocaleString() : 'Not yet';
  const findings = latest?.findings || [];
  const reports = (site.reports || []).map(report => `<li><a href="${API.siteMonitor.report(site.id, report.id)}">${sanitize(date(report.checkedOn))} — ${sanitize(report.status)}</a></li>`).join('');
  const reportEvidence = findings.map(finding => `<li><strong>${sanitize(finding.severity)}</strong> ${sanitize(finding.path)} — ${sanitize(finding.field)}: ${sanitize(finding.before)} → ${sanitize(finding.after)}</li>`).join('');
  return `<section class="zip-coordinate-panel"><h3>${sanitize(site.label)}</h3>
    <p>${sanitize(site.origin)} · ${site.paths.map(sanitize).join(', ')}</p>
    ${site.demo ? '<p>Demonstration site</p>' : `<p>Publish this exact token at <code>${sanitize(site.origin)}/.well-known/christopherbell-site-monitor.txt</code>:</p><pre>${sanitize(site.token)}</pre><p>Ownership is checked before every capture or comparison.</p>`}
    <p>Baseline: ${sanitize(date(site.baselineOn))} · Last attempt: ${sanitize(date(site.lastAttempt))}</p>
    <p>Latest result: <strong>${sanitize(latest?.status || 'No checks yet')}</strong></p>
    <button type="button" class="btn btn-secondary" data-monitor-action="baseline" data-site="${sanitize(site.id)}">${site.baselineOn ? 'Replace baseline' : 'Verify and capture baseline'}</button>
    <button type="button" class="btn btn-primary" data-monitor-action="check" data-site="${sanitize(site.id)}" ${site.baselineOn ? '' : 'disabled'}>Check now</button>
    <button type="button" class="btn btn-secondary" data-monitor-action="delete" data-site="${sanitize(site.id)}">Remove website</button>
    <ul>${reportEvidence}</ul><h4>Latest reports</h4><ul>${reports || '<li>No reports yet.</li>'}</ul>
    <p>Daily checks begin after a healthy baseline is captured. Removing a website deletes its saved baseline and reports.</p>
  </section>`;
}

if (typeof document !== 'undefined') {
  const message = document.getElementById('monitorMessage');
  const workspace = document.getElementById('monitorWorkspace');
  const signedOut = document.getElementById('monitorSignedOut');
  const sites = document.getElementById('monitorSites');
  let busy = false;
  const render = state => { sites.innerHTML = state.sites.map(monitorSiteHtml).join(''); };
  async function perform(request, pending) {
    if (busy) return;
    busy = true;
    const controls = Array.from(workspace.querySelectorAll('button'), button => [button, button.disabled]);
    controls.forEach(([button]) => { button.disabled = true; });
    message.textContent = pending;
    try {
      render(await request());
      message.textContent = 'Workspace updated. Review the result and coverage before accepting changes.';
    } catch (failure) {
      message.textContent = failure.message || 'The check could not complete. Please retry.';
      if (failure.status === 401 || failure.status === 403) {
        workspace.hidden = true; signedOut.hidden = false;
      }
    } finally {
      busy = false;
      controls.forEach(([button, wasDisabled]) => { if (button.isConnected) button.disabled = wasDisabled; });
    }
  }
  document.getElementById('monitorCreate')?.addEventListener('submit', event => {
    event.preventDefault();
    void perform(async () => {
      const state = await fetchJson(API.siteMonitor.sites, { method: 'POST', body: JSON.stringify({
        label: document.getElementById('monitorLabel').value,
        origin: document.getElementById('monitorOrigin').value,
        paths: monitorPagePaths(document.getElementById('monitorPaths').value), demo: false,
      }) });
      event.target.reset(); document.getElementById('monitorPaths').value = '/'; return state;
    }, 'Adding website…');
  });
  document.getElementById('monitorDemo')?.addEventListener('click', () => {
    void perform(() => fetchJson(API.siteMonitor.sites, { method: 'POST',
      body: JSON.stringify({ label: 'Demonstration website', demo: true }) }), 'Adding demonstration…');
  });
  sites?.addEventListener('click', event => {
    const button = event.target.closest('button[data-monitor-action]');
    if (!button || busy) return;
    const { monitorAction: action, site: id } = button.dataset;
    if (action === 'delete' && !window.confirm('Remove this website and all its reports?')) return;
    if (action === 'baseline' && !window.confirm('Accept a new baseline only if these pages represent the intended website. Continue?')) return;
    void perform(() => fetchJson(action === 'delete' ? API.siteMonitor.site(id)
      : API.siteMonitor.action(id, action), { method: action === 'delete' ? 'DELETE' : 'POST' }),
    action === 'delete' ? 'Removing website…' : 'Checking configured pages. This can take up to 45 seconds…');
  });
  if (isLoggedIn()) {
    workspace.hidden = false; signedOut.hidden = true;
    void perform(() => fetchJson(API.siteMonitor.workspace), 'Loading your workspace…');
  }
}
