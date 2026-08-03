import assert from 'node:assert/strict';
import test from 'node:test';

const wflUi = await import('../../main/resources/static/js/lib/wfl-ui.js')
  .catch(cause => ({ loadFailure: cause }));

test('WFL navigation exposes the canonical Top 10 Liked route', () => {
  assert.ifError(wflUi.loadFailure);

  const navigation = withoutTrailingWhitespace(wflUi.wflSecondaryNavigation('top-liked'));
  assert.match(navigation, /<a class="active" href="\/wfl\/top-liked">Top 10 Liked<\/a>/);
  assert.doesNotMatch(navigation, /Rated|top-rated/);
});

function withoutTrailingWhitespace(value) {
  return value.replace(/[ \t]+$/gm, '');
}

test('WFL address helper preserves list and profile field policies', () => {
  assert.ifError(wflUi.loadFailure);
  const address = {
    street1: '1 Main St',
    street2: 'Suite 2',
    city: 'Austin',
    state: 'TX',
    postalCode: '78701',
  };

  assert.equal(wflUi.restaurantAddressLine(address), '1 Main St, Austin, TX, 78701');
  assert.equal(
    wflUi.restaurantAddressLine(address, true),
    '1 Main St, Suite 2, Austin, TX, 78701',
  );
  assert.equal(wflUi.restaurantAddressLine(), '');
});

test('WFL cuisine and vote summaries preserve display fallbacks', () => {
  assert.ifError(wflUi.loadFailure);

  assert.equal(wflUi.formatCuisine('BARBECUE_tex-mex / vegan|COFFEE'),
    'Barbecue Tex Mex/ Vegan| Coffee');
  assert.equal(wflUi.formatCuisine(null), '');
  assert.deepEqual(wflUi.voteSummary({
    upVotes: '10', downVotes: '2', voteCount: '12', myVote: 'UP',
  }), {
    upVotes: 10,
    downVotes: 2,
    voteCount: 12,
    myVote: 'UP',
    approvalPercentage: 83,
    overall: '83% liked · 10 up · 2 down',
  });
  assert.deepEqual(wflUi.voteSummary({ upVotes: 'bad', voteCount: 0, myVote: 'SIDEWAYS' }), {
    upVotes: 0,
    downVotes: 0,
    voteCount: 0,
    myVote: null,
    approvalPercentage: null,
    overall: 'No votes yet',
  });
});
