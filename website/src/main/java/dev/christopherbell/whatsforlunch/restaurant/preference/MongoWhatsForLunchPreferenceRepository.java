package dev.christopherbell.whatsforlunch.restaurant.preference;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreference;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Kind-scoped Mongo implementation of the WFL preference persistence port. */
@MongoPersistence
@Repository
public class MongoWhatsForLunchPreferenceRepository
    extends KindScopedRepositorySupport<WhatsForLunchPreference>
    implements WhatsForLunchPreferenceRepository {
  public MongoWhatsForLunchPreferenceRepository(DomainMongoOperationsFactory factory) {
    super(factory, WhatsForLunchPreference.class);
  }
  @Override public WhatsForLunchPreference save(WhatsForLunchPreference preference) {
    return saveValue(preference);
  }
  @Override public Optional<WhatsForLunchPreference> findById(String accountId) {
    return findValueById(accountId);
  }
}
