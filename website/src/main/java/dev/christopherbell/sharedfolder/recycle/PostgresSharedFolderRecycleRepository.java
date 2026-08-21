package dev.christopherbell.sharedfolder.recycle;

import static dev.christopherbell.persistence.jooq.shared_folder.Tables.RECYCLE_ITEM;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import dev.christopherbell.persistence.jooq.shared_folder.tables.records.RecycleItemRecord;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

/** PostgreSQL implementation of recoverable shared-folder recycle metadata. */
@PostgresPersistence
public class PostgresSharedFolderRecycleRepository implements SharedFolderRecycleRepository {
  private final DSLContext database;

  public PostgresSharedFolderRecycleRepository(DSLContext database) { this.database = database; }

  @Override public SharedFolderRecycleItem save(SharedFolderRecycleItem item) {
    String originalPath = PostgresqlRelativePath.require(item.originalPath(), "Recycle original path");
    String payloadKey = PostgresqlRelativePath.require(item.payloadKey(), "Recycle payload key");
    String replacementKey = item.replacementKey() == null ? null
        : PostgresqlRelativePath.require(item.replacementKey(), "Recycle replacement key");
    database.insertInto(RECYCLE_ITEM).set(RECYCLE_ITEM.RECYCLE_ITEM_ID, item.id())
        .set(RECYCLE_ITEM.ORIGINAL_PATH, originalPath).set(RECYCLE_ITEM.DELETED_BY_ACCOUNT_ID, item.deletedByAccountId())
        .set(RECYCLE_ITEM.DELETED_AT, item.deletedAt().atOffset(ZoneOffset.UTC))
        .set(RECYCLE_ITEM.EXPIRES_AT, item.expiresAt().atOffset(ZoneOffset.UTC))
        .set(RECYCLE_ITEM.PAYLOAD_KEY, payloadKey).set(RECYCLE_ITEM.SIZE_BYTES, item.size())
        .set(RECYCLE_ITEM.IS_DIRECTORY, item.directory()).set(RECYCLE_ITEM.SOURCE_FINGERPRINT, item.sourceFingerprint())
        .set(RECYCLE_ITEM.STATE, item.state().name()).set(RECYCLE_ITEM.REPLACEMENT_KEY, replacementKey)
        .set(RECYCLE_ITEM.REPLACEMENT_FINGERPRINT, item.replacementFingerprint())
        .set(RECYCLE_ITEM.SOURCE_IDENTITY, item.sourceIdentity())
        .set(RECYCLE_ITEM.RETRY_AFTER, item.retryAfter().atOffset(ZoneOffset.UTC))
        .onConflict(RECYCLE_ITEM.RECYCLE_ITEM_ID).doUpdate()
        .set(RECYCLE_ITEM.ORIGINAL_PATH, originalPath).set(RECYCLE_ITEM.DELETED_BY_ACCOUNT_ID, item.deletedByAccountId())
        .set(RECYCLE_ITEM.DELETED_AT, item.deletedAt().atOffset(ZoneOffset.UTC))
        .set(RECYCLE_ITEM.EXPIRES_AT, item.expiresAt().atOffset(ZoneOffset.UTC))
        .set(RECYCLE_ITEM.PAYLOAD_KEY, payloadKey).set(RECYCLE_ITEM.SIZE_BYTES, item.size())
        .set(RECYCLE_ITEM.IS_DIRECTORY, item.directory()).set(RECYCLE_ITEM.SOURCE_FINGERPRINT, item.sourceFingerprint())
        .set(RECYCLE_ITEM.STATE, item.state().name()).set(RECYCLE_ITEM.REPLACEMENT_KEY, replacementKey)
        .set(RECYCLE_ITEM.REPLACEMENT_FINGERPRINT, item.replacementFingerprint())
        .set(RECYCLE_ITEM.SOURCE_IDENTITY, item.sourceIdentity())
        .set(RECYCLE_ITEM.RETRY_AFTER, item.retryAfter().atOffset(ZoneOffset.UTC)).execute();
    return item;
  }

  @Override public Optional<SharedFolderRecycleItem> findById(String id) {
    return database.selectFrom(RECYCLE_ITEM).where(RECYCLE_ITEM.RECYCLE_ITEM_ID.eq(id))
        .fetchOptional(PostgresSharedFolderRecycleRepository::map);
  }
  @Override public void deleteById(String id) {
    database.deleteFrom(RECYCLE_ITEM).where(RECYCLE_ITEM.RECYCLE_ITEM_ID.eq(id)).execute();
  }

  @Override public Slice<SharedFolderRecycleItem> findByStateOrderByDeletedAtDescIdDesc(
      SharedFolderRecycleState state, Pageable page) {
    int size = page.isPaged() ? page.getPageSize() : Integer.MAX_VALUE - 1;
    var rows = database.selectFrom(RECYCLE_ITEM).where(RECYCLE_ITEM.STATE.eq(state.name()))
        .orderBy(RECYCLE_ITEM.DELETED_AT.desc(), RECYCLE_ITEM.RECYCLE_ITEM_ID.desc())
        .limit(page.isPaged() ? size + 1 : size)
        .offset(page.isPaged() ? Math.toIntExact(page.getOffset()) : 0)
        .fetch(PostgresSharedFolderRecycleRepository::map);
    boolean next = page.isPaged() && rows.size() > size;
    return new SliceImpl<>(next ? List.copyOf(rows.subList(0, size)) : List.copyOf(rows), page, next);
  }

  @Override public List<SharedFolderRecycleItem>
      findByStateAndExpiresAtBeforeAndRetryAfterLessThanEqualOrderByExpiresAtAscIdAsc(
          SharedFolderRecycleState state, Instant cutoff, Instant retryDue, Pageable page) {
    return limited(RECYCLE_ITEM.STATE.eq(state.name())
        .and(RECYCLE_ITEM.EXPIRES_AT.lt(cutoff.atOffset(ZoneOffset.UTC)))
        .and(RECYCLE_ITEM.RETRY_AFTER.le(retryDue.atOffset(ZoneOffset.UTC))), page,
        RECYCLE_ITEM.EXPIRES_AT.asc(), RECYCLE_ITEM.RECYCLE_ITEM_ID.asc());
  }

  @Override public List<SharedFolderRecycleItem>
      findByStateInAndRetryAfterLessThanEqualOrderByDeletedAtAscIdAsc(
          List<SharedFolderRecycleState> states, Instant retryDue, Pageable page) {
    return limited(RECYCLE_ITEM.STATE.in(states.stream().map(Enum::name).toList())
        .and(RECYCLE_ITEM.RETRY_AFTER.le(retryDue.atOffset(ZoneOffset.UTC))), page,
        RECYCLE_ITEM.DELETED_AT.asc(), RECYCLE_ITEM.RECYCLE_ITEM_ID.asc());
  }

  private List<SharedFolderRecycleItem> limited(Condition condition, Pageable page,
      org.jooq.SortField<?>... order) {
    var query = database.selectFrom(RECYCLE_ITEM).where(condition).orderBy(order);
    return page.isPaged() ? query.limit(page.getPageSize()).offset(Math.toIntExact(page.getOffset()))
        .fetch(PostgresSharedFolderRecycleRepository::map)
        : query.fetch(PostgresSharedFolderRecycleRepository::map);
  }

  private static SharedFolderRecycleItem map(RecycleItemRecord row) {
    return new SharedFolderRecycleItem(row.getRecycleItemId(), row.getOriginalPath(),
        row.getDeletedByAccountId(), row.getDeletedAt().toInstant(), row.getExpiresAt().toInstant(),
        row.getPayloadKey(), row.getSizeBytes(), row.getIsDirectory(), row.getSourceFingerprint(),
        SharedFolderRecycleState.valueOf(row.getState()), row.getReplacementKey(),
        row.getReplacementFingerprint(), row.getSourceIdentity(), row.getRetryAfter().toInstant());
  }
}
