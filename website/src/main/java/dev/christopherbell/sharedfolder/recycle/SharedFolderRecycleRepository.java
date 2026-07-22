package dev.christopherbell.sharedfolder.recycle;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Persistence boundary for recoverable recycle metadata. */
@Repository
public interface SharedFolderRecycleRepository
    extends MongoRepository<SharedFolderRecycleItem, String> {
  Slice<SharedFolderRecycleItem> findByStateOrderByDeletedAtDescIdDesc(
      SharedFolderRecycleState state, Pageable page);

  List<SharedFolderRecycleItem>
      findByStateAndExpiresAtBeforeAndRetryAfterLessThanEqualOrderByExpiresAtAscIdAsc(
          SharedFolderRecycleState state, Instant cutoff, Instant retryDue, Pageable page);

  List<SharedFolderRecycleItem>
      findByStateInAndRetryAfterLessThanEqualOrderByDeletedAtAscIdAsc(
          List<SharedFolderRecycleState> states, Instant retryDue, Pageable page);
}
