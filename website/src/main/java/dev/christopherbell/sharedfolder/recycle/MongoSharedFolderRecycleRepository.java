package dev.christopherbell.sharedfolder.recycle;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public final class MongoSharedFolderRecycleRepository
    extends KindScopedRepositorySupport<SharedFolderRecycleItem>
    implements SharedFolderRecycleRepository {
  public MongoSharedFolderRecycleRepository(DomainMongoOperationsFactory factory) { super(factory, SharedFolderRecycleItem.class); }
  @Override public SharedFolderRecycleItem save(SharedFolderRecycleItem value) { return saveValue(value); }
  @Override public Optional<SharedFolderRecycleItem> findById(String id) { return findValueById(id); }
  @Override public void deleteById(String id) { super.deleteById(id); }
  @Override public Slice<SharedFolderRecycleItem> findByStateOrderByDeletedAtDescIdDesc(
      SharedFolderRecycleState state, Pageable page) {
    return slice(Query.query(Criteria.where("state").is(state))
        .with(Sort.by(Sort.Order.desc("deletedAt"), Sort.Order.desc("id"))), page);
  }
  @Override public List<SharedFolderRecycleItem>
      findByStateAndExpiresAtBeforeAndRetryAfterLessThanEqualOrderByExpiresAtAscIdAsc(
          SharedFolderRecycleState state, Instant cutoff, Instant retryDue, Pageable page) {
    return find(Query.query(Criteria.where("state").is(state).and("expiresAt").lt(cutoff)
        .and("retryAfter").lte(retryDue))
        .with(Sort.by(Sort.Order.asc("expiresAt"), Sort.Order.asc("id"))), page);
  }
  @Override public List<SharedFolderRecycleItem>
      findByStateInAndRetryAfterLessThanEqualOrderByDeletedAtAscIdAsc(
          List<SharedFolderRecycleState> states, Instant retryDue, Pageable page) {
    return find(Query.query(Criteria.where("state").in(states).and("retryAfter").lte(retryDue))
        .with(Sort.by(Sort.Order.asc("deletedAt"), Sort.Order.asc("id"))), page);
  }
  private Slice<SharedFolderRecycleItem> slice(Query query, Pageable page) {
    var values = find(query, page); return new SliceImpl<>(values, page, values.size() == page.getPageSize());
  }
}
