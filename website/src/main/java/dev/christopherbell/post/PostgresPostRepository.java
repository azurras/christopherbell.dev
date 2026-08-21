package dev.christopherbell.post;

import static dev.christopherbell.persistence.jooq.social.Tables.POST;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_EDIT_AUDIT;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_LINK_PREVIEW;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_TOPIC;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.post.editing.PostEditAuditEvent;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostLinkPreview;
import dev.christopherbell.post.model.PostTopic;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** PostgreSQL implementation of the post persistence port. */
@PostgresPersistence
public class PostgresPostRepository implements PostRepository {
  private final DSLContext database;

  public PostgresPostRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public Post save(Post post) {
    return database.transactionResult(configuration -> save(DSL.using(configuration), post));
  }

  private static Post save(DSLContext transaction, Post post) {
    transaction.insertInto(POST)
        .set(POST.POST_ID, post.getId())
        .set(POST.ACCOUNT_ID, post.getAccountId())
        .set(POST.POST_TEXT, post.getText())
        .set(POST.ROOT_POST_ID, post.getRootId())
        .set(POST.PARENT_POST_ID, post.getParentId())
        .set(POST.THREAD_LEVEL, valueOrZero(post.getLevel()))
        .set(POST.CREATED_ON, timestamp(post.getCreatedOn()))
        .set(POST.LAST_UPDATED_ON, timestamp(post.getLastUpdatedOn()))
        .set(POST.EDITED_ON, timestamp(post.getEditedOn()))
        .set(POST.EXPIRES_ON, timestamp(post.getExpiresOn()))
        .set(POST.FEDERATION_OUTBOUND_ELIGIBLE, post.isFederationOutboundEligible())
        .set(POST.LAST_EXTENDED_ON, timestamp(post.getLastExtendedOn()))
        .set(POST.LIKES_COUNT, valueOrZero(post.getLikesCount()))
        .set(POST.THREAD_REPLY_LIKES_COUNT, valueOrZero(post.getThreadReplyLikesCount()))
        .set(POST.THREAD_REPLY_COUNT, valueOrZero(post.getThreadReplyCount()))
        .onConflict(POST.POST_ID)
        .doUpdate()
        .set(POST.ACCOUNT_ID, post.getAccountId())
        .set(POST.POST_TEXT, post.getText())
        .set(POST.ROOT_POST_ID, post.getRootId())
        .set(POST.PARENT_POST_ID, post.getParentId())
        .set(POST.THREAD_LEVEL, valueOrZero(post.getLevel()))
        .set(POST.LAST_UPDATED_ON, timestamp(post.getLastUpdatedOn()))
        .set(POST.EDITED_ON, timestamp(post.getEditedOn()))
        .set(POST.EXPIRES_ON, timestamp(post.getExpiresOn()))
        .set(POST.FEDERATION_OUTBOUND_ELIGIBLE, post.isFederationOutboundEligible())
        .set(POST.LAST_EXTENDED_ON, timestamp(post.getLastExtendedOn()))
        .set(POST.LIKES_COUNT, valueOrZero(post.getLikesCount()))
        .set(POST.THREAD_REPLY_LIKES_COUNT, valueOrZero(post.getThreadReplyLikesCount()))
        .set(POST.THREAD_REPLY_COUNT, valueOrZero(post.getThreadReplyCount()))
        .set(POST.VERSION, POST.VERSION.plus(1L))
        .execute();
    replaceChildren(transaction, post);
    return findById(transaction, post.getId()).orElseThrow();
  }

  private static void replaceChildren(DSLContext transaction, Post post) {
    transaction.deleteFrom(POST_EDIT_AUDIT).where(POST_EDIT_AUDIT.POST_ID.eq(post.getId())).execute();
    transaction.deleteFrom(POST_TOPIC).where(POST_TOPIC.POST_ID.eq(post.getId())).execute();
    transaction.deleteFrom(POST_LINK_PREVIEW)
        .where(POST_LINK_PREVIEW.POST_ID.eq(post.getId())).execute();
    var audits = post.getEditAudit() == null ? List.<PostEditAuditEvent>of() : post.getEditAudit();
    for (var ordinal = 0; ordinal < audits.size(); ordinal++) {
      var audit = audits.get(ordinal);
      transaction.insertInto(POST_EDIT_AUDIT)
          .set(POST_EDIT_AUDIT.POST_ID, post.getId())
          .set(POST_EDIT_AUDIT.ORDINAL, ordinal)
          .set(POST_EDIT_AUDIT.EDITOR_ACCOUNT_ID, audit.editorAccountId())
          .set(POST_EDIT_AUDIT.BEFORE_TEXT, audit.beforeText())
          .set(POST_EDIT_AUDIT.AFTER_TEXT, audit.afterText())
          .set(POST_EDIT_AUDIT.EDITED_ON, timestamp(audit.editedOn()))
          .execute();
    }
    var topics = post.getTopics() == null ? List.<PostTopic>of() : post.getTopics();
    for (var ordinal = 0; ordinal < topics.size(); ordinal++) {
      var topic = topics.get(ordinal);
      transaction.insertInto(POST_TOPIC)
          .set(POST_TOPIC.POST_ID, post.getId())
          .set(POST_TOPIC.ORDINAL, ordinal)
          .set(POST_TOPIC.CANONICAL, topic.canonical())
          .set(POST_TOPIC.DISPLAY, topic.display())
          .execute();
    }
    var previews = post.getLinkPreviews() == null
        ? List.<PostLinkPreview>of() : post.getLinkPreviews();
    for (var ordinal = 0; ordinal < previews.size(); ordinal++) {
      var preview = previews.get(ordinal);
      transaction.insertInto(POST_LINK_PREVIEW)
          .set(POST_LINK_PREVIEW.POST_ID, post.getId())
          .set(POST_LINK_PREVIEW.ORDINAL, ordinal)
          .set(POST_LINK_PREVIEW.URL, preview.url())
          .set(POST_LINK_PREVIEW.DOMAIN_NAME, preview.domain())
          .set(POST_LINK_PREVIEW.TITLE, preview.title())
          .set(POST_LINK_PREVIEW.DESCRIPTION, preview.description())
          .set(POST_LINK_PREVIEW.IMAGE_URL, preview.imageUrl())
          .execute();
    }
  }

  @Override
  public Optional<Post> findById(String id) {
    return findById(database, id);
  }

  private static Optional<Post> findById(DSLContext context, String id) {
    return context.selectFrom(POST).where(POST.POST_ID.eq(id))
        .fetchOptional(record -> PostgresPostMapper.map(context, record));
  }

  @Override
  public void delete(Post post) {
    deleteById(post.getId());
  }

  @Override
  public void deleteById(String id) {
    database.deleteFrom(POST).where(POST.POST_ID.eq(id)).execute();
  }

  @Override
  public void deleteAll(Iterable<Post> posts) {
    var ids = new ArrayList<String>();
    posts.forEach(post -> ids.add(post.getId()));
    if (!ids.isEmpty()) database.deleteFrom(POST).where(POST.POST_ID.in(ids)).execute();
  }

  @Override
  public long count() {
    return database.fetchCount(POST);
  }

  @Override
  public List<Post> findByAccountIdOrderByCreatedOnDesc(String accountId) {
    return findByAccountIdOrderByCreatedOnDesc(accountId, Pageable.unpaged());
  }

  @Override
  public List<Post> findByAccountIdOrderByCreatedOnDesc(String accountId, Pageable pageable) {
    return fetch(POST.ACCOUNT_ID.eq(accountId),
        List.of(POST.CREATED_ON.desc(), POST.POST_ID.desc()), pageable);
  }

  @Override
  public Page<Post> findAll(Pageable pageable) {
    var order = pageable.getSort().isSorted() ? sort(pageable.getSort())
        : List.<SortField<?>>of(POST.POST_ID.asc());
    return page(POST.POST_ID.isNotNull(), order, pageable);
  }

  @Override
  public List<Post> findByRootIdOrderByCreatedOnAsc(String rootId) {
    return fetch(POST.ROOT_POST_ID.eq(rootId),
        List.of(POST.CREATED_ON.asc(), POST.POST_ID.asc()), Pageable.unpaged());
  }

  @Override
  public List<Post> findByExpiresOnLessThanEqual(Instant cutoff, Pageable pageable) {
    return fetch(POST.EXPIRES_ON.le(timestamp(cutoff)),
        List.of(POST.EXPIRES_ON.asc(), POST.POST_ID.asc()), pageable);
  }

  @Override
  public long countByExpiresOnAfter(Instant cutoff) {
    return database.fetchCount(POST, POST.EXPIRES_ON.gt(timestamp(cutoff)));
  }

  @Override
  public Page<Post> findByExpiresOnAfter(Instant cutoff, Pageable pageable) {
    return page(POST.EXPIRES_ON.gt(timestamp(cutoff)), sort(pageable.getSort()), pageable);
  }

  @Override
  public List<Post> findByExpiresOnIsNull(Pageable pageable) {
    return fetch(POST.EXPIRES_ON.isNull(), sort(pageable.getSort()), pageable);
  }

  @Override
  public long countByAccountIdAndParentIdIsNull(String accountId) {
    return database.fetchCount(POST,
        POST.ACCOUNT_ID.eq(accountId).and(POST.PARENT_POST_ID.isNull()));
  }

  @Override
  public long countByAccountIdAndParentIdIsNotNull(String accountId) {
    return database.fetchCount(POST,
        POST.ACCOUNT_ID.eq(accountId).and(POST.PARENT_POST_ID.isNotNull()));
  }

  @Override
  public List<Post> findFederationEligibleAfter(Instant createdOn, String postId, int limit) {
    var condition = POST.FEDERATION_OUTBOUND_ELIGIBLE.isTrue();
    if (createdOn != null && postId != null) {
      var boundary = timestamp(createdOn);
      condition = condition.and(POST.CREATED_ON.gt(boundary)
          .or(POST.CREATED_ON.eq(boundary).and(POST.POST_ID.gt(postId))));
    }
    var records = database.selectFrom(POST)
        .where(condition)
        .orderBy(POST.CREATED_ON.asc(), POST.POST_ID.asc())
        .limit(limit)
        .fetch();
    return PostgresPostMapper.mapAll(database, records);
  }

  @Override
  public List<Post> findFederationOutboxPage(
      String accountId, Instant createdOn, String postId, int limit, Instant expiresAfter) {
    var condition = POST.ACCOUNT_ID.eq(accountId)
        .and(POST.FEDERATION_OUTBOUND_ELIGIBLE.isTrue())
        .and(POST.EXPIRES_ON.gt(timestamp(expiresAfter)))
        .and(POST.CREATED_ON.isNotNull());
    if (createdOn != null && postId != null) {
      var boundary = timestamp(createdOn);
      condition = condition.and(POST.CREATED_ON.lt(boundary)
          .or(POST.CREATED_ON.eq(boundary).and(POST.POST_ID.lt(postId))));
    }
    var records = database.selectFrom(POST)
        .where(condition)
        .orderBy(POST.CREATED_ON.desc(), POST.POST_ID.desc())
        .limit(limit)
        .fetch();
    return PostgresPostMapper.mapAll(database, records);
  }

  private List<Post> fetch(
      org.jooq.Condition condition, List<SortField<?>> order, Pageable pageable) {
    var query = database.selectFrom(POST).where(condition).orderBy(order);
    var records = pageable.isPaged()
        ? query.limit(pageable.getPageSize()).offset(Math.toIntExact(pageable.getOffset()))
            .fetch()
        : query.fetch();
    return PostgresPostMapper.mapAll(database, records);
  }

  private Page<Post> page(
      org.jooq.Condition condition, List<SortField<?>> order, Pageable pageable) {
    var values = fetch(condition, order, pageable);
    return new PageImpl<>(values, pageable, database.fetchCount(POST, condition));
  }

  private static List<SortField<?>> sort(Sort sort) {
    var fields = new ArrayList<SortField<?>>();
    for (var order : sort) {
      var field = switch (order.getProperty()) {
        case "id" -> POST.POST_ID;
        case "createdOn" -> POST.CREATED_ON;
        case "expiresOn" -> POST.EXPIRES_ON;
        case "lastExtendedOn" -> POST.LAST_EXTENDED_ON;
        default -> throw new IllegalArgumentException(
            "Unsupported post sort property: " + order.getProperty());
      };
      fields.add(order.isAscending() ? field.asc() : field.desc());
    }
    if (fields.isEmpty()) fields.add(POST.POST_ID.asc());
    fields.add(POST.POST_ID.asc());
    return fields;
  }

  private static int valueOrZero(Integer value) {
    return value == null ? 0 : value;
  }

  private static OffsetDateTime timestamp(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
