package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Kind-scoped Mongo implementation of the restaurant persistence port. */
@MongoPersistence
@Repository
public class MongoRestaurantRepository extends KindScopedRepositorySupport<Restaurant>
    implements RestaurantRepository {
  public MongoRestaurantRepository(DomainMongoOperationsFactory factory) {
    super(factory, Restaurant.class);
  }

  @Override public Restaurant save(Restaurant restaurant) {
    RestaurantLocationIntegrity.requireGenuine(restaurant);
    return saveValue(restaurant);
  }
  @Override public Optional<Restaurant> findById(String id) { return findValueById(id); }
  @Override public void delete(Restaurant restaurant) { super.deleteById(restaurant.getId()); }
  @Override public void deleteAll(Iterable<Restaurant> restaurants) {
    deleteAllValues(restaurants, Restaurant::getId);
  }
  @Override public List<Restaurant> findAll() { return find(new Query()); }
  @Override public long count() { return mongo.count(new Query()); }
  @Override public Page<Restaurant> findAll(Pageable pageable) { return page(new Query(), pageable); }
  @Override public List<Restaurant> findAllById(Iterable<String> ids) {
    var values = new ArrayList<String>();
    ids.forEach(values::add);
    return values.isEmpty() ? List.of()
        : find(Query.query(Criteria.where("id").in(values)));
  }
  @Override public Optional<Restaurant> findByNormalizedName(String normalizedName) {
    return findOne(Query.query(Criteria.where("normalizedName").is(normalizedName)));
  }
  @Override public List<Restaurant> findByDedupeKeyIn(List<String> dedupeKeys) {
    return dedupeKeys.isEmpty() ? List.of()
        : find(Query.query(Criteria.where("dedupeKey").in(dedupeKeys)));
  }
  @Override public List<Restaurant> findByCoordinateBounds(
      double minLatitude, double maxLatitude, double minLongitude, double maxLongitude) {
    return find(Query.query(Criteria.where("address.latitude").gte(minLatitude).lte(maxLatitude)
        .and("address.longitude").gte(minLongitude).lte(maxLongitude)));
  }
}
