import assert from 'node:assert/strict';
import test from 'node:test';

import { API } from '../../main/resources/static/js/lib/api.js';
import {
  activityPageNavigation,
  moderationActivitySummary,
  moderationReasonValue,
  parseActivityPage,
} from '../../main/resources/static/js/lib/back-office-activity.js';

test('audit page URL sends stable pagination and nonblank filters', () => {
  assert.equal(API.admin.activityPage({
    page: 2,
    size: 25,
    action: 'REPORT_RESOLVED',
    targetType: 'REPORT',
    actor: 'azurras.*',
    from: '2026-07-01T00:00:00.000Z',
    to: '2026-07-26T23:59:59.000Z',
  }), '/api/admin/activity/2026-07-26?page=2&size=25&action=REPORT_RESOLVED&targetType=REPORT&actor=azurras.*&from=2026-07-01T00%3A00%3A00.000Z&to=2026-07-26T23%3A59%3A59.000Z');
});

test('audit page parser and navigation enforce authoritative bounds', () => {
  const page = { items: [{ id: 'a1' }], page: 1, size: 25, totalElements: 26, totalPages: 2 };
  assert.deepEqual(parseActivityPage(page), page);
  assert.deepEqual(activityPageNavigation(page), {
    previousDisabled: false,
    nextDisabled: true,
    label: 'Page 2 of 2',
  });
  assert.throws(() => parseActivityPage({ ...page, totalPages: -1 }), /invalid audit page/i);
});

test('moderation audit summary exposes only allowlisted state and a bounded reason', () => {
  assert.deepEqual(moderationActivitySummary({
    reason: ' Confirmed abuse. ',
    beforeValues: { status: 'OPEN', password: 'secret' },
    afterValues: { status: 'RESOLVED', email: 'private@example.com' },
  }), {
    reason: 'Confirmed abuse.',
    transition: 'status: OPEN → RESOLVED',
  });
  assert.equal(moderationReasonValue(' Evidence reviewed. '), 'Evidence reviewed.');
  assert.equal(moderationReasonValue('x'.repeat(501)), '');
});
