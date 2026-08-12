package dev.christopherbell.sharedfolder.recycle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

/** Persistence boundary for recoverable recycle metadata. */
public interface SharedFolderRecycleRepository {
  SharedFolderRecycleItem save(SharedFolderRecycleItem item);
  Optional<SharedFolderRecycleItem> findById(String id);
  void deleteById(String id);
  Slice<SharedFolderRecycleItem> findByStateOrderByDeletedAtDescIdDesc(
      SharedFolderRecycleState state, Pageable page);

  List<SharedFolderRecycleItem>
      findByStateAndExpiresAtBeforeAndRetryAfterLessThanEqualOrderByExpiresAtAscIdAsc(
          SharedFolderRecycleState state, Instant cutoff, Instant retryDue, Pageable page);

  List<SharedFolderRecycleItem>
      findByStateInAndRetryAfterLessThanEqualOrderByDeletedAtAscIdAsc(
          List<SharedFolderRecycleState> states, Instant retryDue, Pageable page);
}
