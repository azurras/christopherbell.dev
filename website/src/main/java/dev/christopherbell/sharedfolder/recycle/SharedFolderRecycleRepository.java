package dev.christopherbell.sharedfolder.recycle;

import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Persistence boundary for recoverable recycle metadata. */
@Repository
public interface SharedFolderRecycleRepository
    extends MongoRepository<SharedFolderRecycleItem, String> {
  List<SharedFolderRecycleItem> findByStateOrderByDeletedAtDesc(SharedFolderRecycleState state);

  List<SharedFolderRecycleItem> findByStateAndExpiresAtBefore(
      SharedFolderRecycleState state, Instant cutoff);

  List<SharedFolderRecycleItem> findByStateIn(List<SharedFolderRecycleState> states);
}
