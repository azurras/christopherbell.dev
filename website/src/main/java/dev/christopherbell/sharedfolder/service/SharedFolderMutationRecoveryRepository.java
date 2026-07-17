package dev.christopherbell.sharedfolder.service;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Bounded owner-scoped access to unfinished conditional replacements. */
public interface SharedFolderMutationRecoveryRepository
    extends MongoRepository<SharedFolderMutationRecovery, String> {
  List<SharedFolderMutationRecovery> findTop100ByOwnerIdOrderByUpdatedAtAsc(String ownerId);
  List<SharedFolderMutationRecovery> findTop100ByOrderByUpdatedAtAsc();
}
