package dev.christopherbell.location.zip;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.location.model.ZipCoordinateImportState;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MongoZipCoordinateImportStateRepository
    extends KindScopedRepositorySupport<ZipCoordinateImportState>
    implements ZipCoordinateImportStateRepository {
  public MongoZipCoordinateImportStateRepository(DomainMongoOperationsFactory factory) { super(factory, ZipCoordinateImportState.class); }
  @Override public Optional<ZipCoordinateImportState> findById(String id) { return findValueById(id); }
  @Override public ZipCoordinateImportState save(ZipCoordinateImportState value) { return saveValue(value); }
}
