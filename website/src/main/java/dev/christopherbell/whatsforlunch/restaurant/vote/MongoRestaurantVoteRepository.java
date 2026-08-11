package dev.christopherbell.whatsforlunch.restaurant.vote;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVote;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Kind-scoped Mongo implementation of the restaurant-vote persistence port. */
@Repository
public final class MongoRestaurantVoteRepository
    extends KindScopedRepositorySupport<RestaurantVote>
    implements RestaurantVoteRepository {
  public MongoRestaurantVoteRepository(DomainMongoOperationsFactory factory) {
    super(factory, RestaurantVote.class);
  }
  @Override public RestaurantVote save(RestaurantVote vote) { return saveValue(vote); }
  @Override public Optional<RestaurantVote> findById(String id) { return findValueById(id); }
  @Override public void deleteById(String id) { super.deleteById(id); }
  @Override public List<RestaurantVote> findByRestaurantIdIn(Collection<String> ids) {
    return ids.isEmpty() ? List.of()
        : find(Query.query(Criteria.where("restaurantId").in(ids)));
  }
  @Override public Optional<RestaurantVote> findByRestaurantIdAndAccountId(
      String restaurantId, String accountId) {
    return findOne(Query.query(Criteria.where("restaurantId").is(restaurantId)
        .and("accountId").is(accountId)));
  }
}
