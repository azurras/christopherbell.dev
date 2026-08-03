import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

test('WFL browser surfaces use the vote endpoint and accessible thumb controls', () => {
  const api = fs.readFileSync('website/src/main/resources/static/js/lib/api.js', 'utf8');
  const picks = fs.readFileSync('website/src/main/resources/static/js/whats-for-lunch.js', 'utf8');
  const list = fs.readFileSync('website/src/main/resources/static/js/wfl-list.js', 'utf8');

  assert.match(api, /voteRestaurant: '.*\/vote'/);
  assert.match(api, /topLiked: \(limit = 10\) => `.*\/top-liked\?limit=/);
  assert.doesNotMatch(api, /rateRestaurant|topRated/);

  for (const script of [picks]) {
    assert.match(script, /lunch-vote-button/);
    assert.match(script, /data-vote="UP"/);
    assert.match(script, /data-vote="DOWN"/);
    assert.match(script, /aria-pressed=/);
    assert.match(script, /voteSummary/);
    assert.doesNotMatch(script, /data-rating|lunch-rating|rateRestaurant|topRated/);
  }

  assert.match(picks, /JSON\.stringify\(\{ restaurantId, vote \}\)/);
  assert.match(list, /lunch-vote-summary/);
  assert.match(list, /voteSummary/);
  assert.doesNotMatch(list, /data-rating|lunch-rating|rateRestaurant|topRated/);
  assert.match(list, /API\.whatsForLunch\.topLiked\(10\)/);
  assert.match(list, /Top 10 Liked/);
  assert.match(list, /WFL restaurants with the highest member approval\./);
});
