package dev.christopherbell.sharedfolder.radio;

import java.util.Optional;

/** Persistence boundary for the one durable shared-folder radio station. */
public interface SharedFolderRadioRepository {
  Optional<SharedFolderRadioDocument> findById(String id);
  SharedFolderRadioDocument save(SharedFolderRadioDocument document);
}
