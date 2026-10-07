package dev.christopherbell.location.zip;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.location.model.ZipCoordinateImportState;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Stores the last successful ZIP coordinate import per source dataset. */
@MongoPersistence
@Repository
public class MongoZipCoordinateImportStateRepository
    extends KindScopedRepositorySupport<ZipCoordinateImportState>
    implements ZipCoordinateImportStateRepository {

  public MongoZipCoordinateImportStateRepository(DomainMongoOperationsFactory factory) {
    super(factory, ZipCoordinateImportState.class);
  }

  @Override
  public Optional<ZipCoordinateImportState> findById(String importStateId) {
    return findValueById(importStateId);
  }

  @Override
  public ZipCoordinateImportState save(ZipCoordinateImportState importState) {
    return saveValue(importState);
  }
}
