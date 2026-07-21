package dev.christopherbell.sharedfolder.recycle;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Persistence boundary for recoverable recycle metadata. */
@Repository
public interface SharedFolderRecycleRepository
    extends MongoRepository<SharedFolderRecycleItem, String> {
  List<SharedFolderRecycleItem> findByStateOrderByDeletedAtDesc(
      SharedFolderRecycleState state, Pageable page);

  List<SharedFolderRecycleItem> findByStateAndExpiresAtBeforeOrderByExpiresAtAsc(
      SharedFolderRecycleState state, Instant cutoff, Pageable page);

  List<SharedFolderRecycleItem> findByStateIn(List<SharedFolderRecycleState> states);
}
