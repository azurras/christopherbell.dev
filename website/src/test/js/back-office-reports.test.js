import assert from 'node:assert/strict';
import test from 'node:test';

import { API } from '../../main/resources/static/js/lib/api.js';
import {
  parseReportPage,
  reportPageNavigation
} from '../../main/resources/static/js/lib/back-office-reports.js';

test('report queue URL sends only bounded nonblank filters', () => {
  assert.equal(API.reports.page({
    page: 2,
    size: 25,
    status: 'OPEN',
    reportType: 'SPAM',
    targetType: 'POST',
    reporter: 'reader.*',
    from: '2026-07-01T00:00:00.000Z',
    to: '2026-07-26T23:59:59.000Z'
  }), '/api/reports/2026-07-26?page=2&size=25&status=OPEN&reportType=SPAM&targetType=POST&reporter=reader.*&from=2026-07-01T00%3A00%3A00.000Z&to=2026-07-26T23%3A59%3A59.000Z');
});

test('report page parser copies items and rejects malformed totals', () => {
  const page = { items: [{ id: 'r1' }], page: 0, size: 25, totalElements: 1, totalPages: 1 };
  assert.deepEqual(parseReportPage(page), page);
  assert.throws(() => parseReportPage({ ...page, totalPages: -1 }), /invalid report page/i);
});

test('report page navigation uses exact server bounds', () => {
  assert.deepEqual(reportPageNavigation({ page: 0, totalPages: 2 }), {
    previousDisabled: true,
    nextDisabled: false,
    label: 'Page 1 of 2'
  });
  assert.deepEqual(reportPageNavigation({ page: 1, totalPages: 2 }), {
    previousDisabled: false,
    nextDisabled: true,
    label: 'Page 2 of 2'
  });
});
