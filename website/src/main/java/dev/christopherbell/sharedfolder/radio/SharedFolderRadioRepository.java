package dev.christopherbell.sharedfolder.radio;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Mongo persistence boundary for the one durable shared-folder radio station. */
@Repository
public interface SharedFolderRadioRepository
    extends MongoRepository<SharedFolderRadioDocument, String> {}
