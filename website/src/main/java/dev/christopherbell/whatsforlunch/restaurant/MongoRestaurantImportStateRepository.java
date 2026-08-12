package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportState;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Kind-scoped Mongo implementation of the restaurant-import state persistence port. */
@Repository
public class MongoRestaurantImportStateRepository
    extends KindScopedRepositorySupport<RestaurantImportState>
    implements RestaurantImportStateRepository {
  public MongoRestaurantImportStateRepository(DomainMongoOperationsFactory factory) {
    super(factory, RestaurantImportState.class);
  }
  @Override public RestaurantImportState save(RestaurantImportState state) {
    return saveValue(state);
  }
  @Override public Optional<RestaurantImportState> findById(String id) {
    return findValueById(id);
  }
}
