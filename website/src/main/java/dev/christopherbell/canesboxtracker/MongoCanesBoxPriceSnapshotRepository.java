package dev.christopherbell.canesboxtracker;

import dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public final class MongoCanesBoxPriceSnapshotRepository
    extends KindScopedRepositorySupport<CanesBoxPriceSnapshot>
    implements CanesBoxPriceSnapshotRepository {
  public MongoCanesBoxPriceSnapshotRepository(DomainMongoOperationsFactory factory) {
    super(factory, CanesBoxPriceSnapshot.class);
  }
  @Override public CanesBoxPriceSnapshot save(CanesBoxPriceSnapshot value) { return saveValue(value); }
  @Override public Optional<CanesBoxPriceSnapshot> findById(String id) { return findValueById(id); }
  @Override public List<CanesBoxPriceSnapshot> findTop60ByOrderByWeekStartDateDesc() {
    return find(new Query(), PageRequest.of(0, 60, Sort.by(Sort.Direction.DESC, "weekStartDate")));
  }
}
