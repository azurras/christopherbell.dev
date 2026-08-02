import assert from 'node:assert/strict';
import test from 'node:test';

const wflUi = await import('../../main/resources/static/js/lib/wfl-ui.js')
  .catch(cause => ({ loadFailure: cause }));

test('WFL navigation preserves active-link markup for every route', () => {
  assert.ifError(wflUi.loadFailure);

  assert.equal(
    withoutTrailingWhitespace(wflUi.wflSecondaryNavigation('top-rated')),
    withoutTrailingWhitespace(`
    <nav class="wfl-secondary-nav" aria-label="What's For Lunch navigation">

        <a class="" href="/wfl">Picks</a>

        <a class="active" href="/wfl/top-rated">Top 10 Rated</a>

        <a class="" href="/wfl/favorites">Favorites</a>

    </nav>
  `),
  );
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

test('WFL cuisine and rating summaries preserve display fallbacks', () => {
  assert.ifError(wflUi.loadFailure);

  assert.equal(wflUi.formatCuisine('BARBECUE_tex-mex / vegan|COFFEE'),
    'Barbecue Tex Mex/ Vegan| Coffee');
  assert.equal(wflUi.formatCuisine(null), '');
  assert.deepEqual(wflUi.ratingSummary({ ratingSum: '9', ratingCount: '2', myRating: '4' }), {
    count: 2,
    myRating: 4,
    overall: '5/5',
  });
  assert.deepEqual(wflUi.ratingSummary({ ratingSum: 'bad', ratingCount: 0 }), {
    count: 0,
    myRating: 0,
    overall: 'No Ratings',
  });
});
