package dev.christopherbell.sharedfolder.radio;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@MongoPersistence
@Repository
public class MongoSharedFolderRadioRepository
    extends KindScopedRepositorySupport<SharedFolderRadioDocument>
    implements SharedFolderRadioRepository {
  public MongoSharedFolderRadioRepository(DomainMongoOperationsFactory factory) { super(factory, SharedFolderRadioDocument.class); }
  @Override public Optional<SharedFolderRadioDocument> findById(String id) { return findValueById(id); }
  @Override public SharedFolderRadioDocument save(SharedFolderRadioDocument value) { return saveValue(value); }
}
