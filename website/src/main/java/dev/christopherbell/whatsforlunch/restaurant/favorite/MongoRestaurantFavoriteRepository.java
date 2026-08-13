package dev.christopherbell.whatsforlunch.restaurant.favorite;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Kind-scoped Mongo implementation of the restaurant-favorite persistence port. */
@MongoPersistence
@Repository
public class MongoRestaurantFavoriteRepository
    extends KindScopedRepositorySupport<RestaurantFavorite>
    implements RestaurantFavoriteRepository {
  public MongoRestaurantFavoriteRepository(DomainMongoOperationsFactory factory) {
    super(factory, RestaurantFavorite.class);
  }
  @Override public RestaurantFavorite save(RestaurantFavorite favorite) {
    return saveValue(favorite);
  }
  @Override public void deleteByRestaurantIdAndAccountId(String restaurantId, String accountId) {
    mongo.remove(Query.query(Criteria.where("restaurantId").is(restaurantId)
        .and("accountId").is(accountId)));
  }
  @Override public List<RestaurantFavorite> findByAccountIdOrderByCreatedOnDesc(String accountId) {
    return find(Query.query(Criteria.where("accountId").is(accountId))
        .with(Sort.by(Sort.Direction.DESC, "createdOn")));
  }
  @Override public List<RestaurantFavorite> findByRestaurantIdInAndAccountId(
      Collection<String> ids, String accountId) {
    return ids.isEmpty() ? List.of() : find(Query.query(Criteria.where("restaurantId").in(ids)
        .and("accountId").is(accountId)));
  }
  @Override public Optional<RestaurantFavorite> findByRestaurantIdAndAccountId(
      String restaurantId, String accountId) {
    return findOne(Query.query(Criteria.where("restaurantId").is(restaurantId)
        .and("accountId").is(accountId)));
  }
}
