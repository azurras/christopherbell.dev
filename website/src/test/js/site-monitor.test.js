import assert from 'node:assert/strict';
import test from 'node:test';
import { monitorPagePaths, monitorSiteHtml } from '../../main/resources/static/js/site-monitor.js';
import { API } from '../../main/resources/static/js/lib/api.js';

test('monitor paths preserve configured coverage and reject excessive or absent pages', () => {
  assert.deepEqual(monitorPagePaths(' /\r\n /contact \n'), ['/', '/contact']);
  assert.throws(() => monitorPagePaths(''), /one to five/);
  assert.throws(() => monitorPagePaths('/a\n/b\n/c\n/d\n/e\n/f'), /one to five/);
});
test('monitor escapes remote text and keeps report links on the protected same-origin API', () => {
  const html = monitorSiteHtml({ id: 'site/one', label: '<img src=x onerror=alert(1)>',
    origin: 'https://example.com', paths: ['/'], token: '<script>bad</script>',
    reports: [{ id: 'report/one', checkedOn: '2026-10-03T00:00:00Z', status: '<script>',
      findings: [{ severity: 'CHANGE', path: '/', field: 'title', before: '<svg>', after: '<script>alert(1)</script>' }] }] });
  assert.ok(!html.includes('<script>'));
  assert.ok(html.includes('&lt;script&gt;'));
  assert.ok(html.includes(API.siteMonitor.report('site/one', 'report/one')));
  assert.match(html, /data-monitor-action="check"[^>]*disabled/);
});
