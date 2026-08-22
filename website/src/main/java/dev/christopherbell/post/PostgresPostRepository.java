package dev.christopherbell.post;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.post.editing.PostEditAuditEvent;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostLinkPreview;
import dev.christopherbell.post.model.PostTopic;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL implementation of the post persistence port. */
@PostgresPersistence
public class PostgresPostRepository implements PostRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final PostgresPostMapper mapper;
  private final String postTable;
  private final String auditTable;
  private final String topicTable;
  private final String previewTable;

  public PostgresPostRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    mapper = new PostgresPostMapper(database, schemas);
    postTable = schemas.qualifiedTable("social", "post");
    auditTable = schemas.qualifiedTable("social", "post_edit_audit");
    topicTable = schemas.qualifiedTable("social", "post_topic");
    previewTable = schemas.qualifiedTable("social", "post_link_preview");
  }

  @Override
  public Post save(Post post) {
    var saved = transactions.execute(ignored -> {
      database.sql("""
              insert into %s (
                post_id, account_id, post_text, root_post_id, parent_post_id, thread_level,
                created_on, last_updated_on, edited_on, expires_on,
                federation_outbound_eligible, last_extended_on, likes_count,
                thread_reply_likes_count, thread_reply_count)
              values (:id, :accountId, :text, :rootId, :parentId, :level, :createdOn,
                :updatedOn, :editedOn, :expiresOn, :federationEligible, :extendedOn,
                :likes, :replyLikes, :replies)
              on conflict (post_id) do update set
                account_id = excluded.account_id, post_text = excluded.post_text,
                root_post_id = excluded.root_post_id, parent_post_id = excluded.parent_post_id,
                thread_level = excluded.thread_level, last_updated_on = excluded.last_updated_on,
                edited_on = excluded.edited_on, expires_on = excluded.expires_on,
                federation_outbound_eligible = excluded.federation_outbound_eligible,
                last_extended_on = excluded.last_extended_on, likes_count = excluded.likes_count,
                thread_reply_likes_count = excluded.thread_reply_likes_count,
                thread_reply_count = excluded.thread_reply_count, version = %s.version + 1
              """.formatted(postTable, postTable))
          .paramSource(parameters(post)).update();
      replaceChildren(post);
      return findById(post.getId()).orElseThrow();
    });
    if (saved == null) throw new IllegalStateException("Post transaction returned no value.");
    return saved;
  }

  private void replaceChildren(Post post) {
    deleteChildren(auditTable, post.getId());
    deleteChildren(topicTable, post.getId());
    deleteChildren(previewTable, post.getId());
    var audits = post.getEditAudit() == null ? List.<PostEditAuditEvent>of() : post.getEditAudit();
    for (int ordinal = 0; ordinal < audits.size(); ordinal++) {
      var audit = audits.get(ordinal);
      database.sql("""
              insert into %s (
                post_id, ordinal, editor_account_id, before_text, after_text, edited_on)
              values (:id, :ordinal, :accountId, :beforeText, :afterText, :editedOn)
              """.formatted(auditTable)).param("id", post.getId()).param("ordinal", ordinal)
          .param("accountId", audit.editorAccountId()).param("beforeText", audit.beforeText())
          .param("afterText", audit.afterText()).param("editedOn", timestamp(audit.editedOn()))
          .update();
    }
    var topics = post.getTopics() == null ? List.<PostTopic>of() : post.getTopics();
    for (int ordinal = 0; ordinal < topics.size(); ordinal++) {
      var topic = topics.get(ordinal);
      database.sql("""
              insert into %s (post_id, ordinal, canonical, display)
              values (:id, :ordinal, :canonical, :display)
              """.formatted(topicTable)).param("id", post.getId()).param("ordinal", ordinal)
          .param("canonical", topic.canonical()).param("display", topic.display()).update();
    }
    var previews = post.getLinkPreviews() == null
        ? List.<PostLinkPreview>of() : post.getLinkPreviews();
    for (int ordinal = 0; ordinal < previews.size(); ordinal++) {
      var preview = previews.get(ordinal);
      database.sql("""
              insert into %s (
                post_id, ordinal, url, domain_name, title, description, image_url)
              values (:id, :ordinal, :url, :domain, :title, :description, :imageUrl)
              """.formatted(previewTable)).param("id", post.getId()).param("ordinal", ordinal)
          .param("url", preview.url()).param("domain", preview.domain())
          .param("title", preview.title()).param("description", preview.description())
          .param("imageUrl", preview.imageUrl()).update();
    }
  }

  @Override
  public Optional<Post> findById(String id) {
    var rows = database.sql("select * from %s where post_id = :id".formatted(postTable))
        .param("id", id).query(mapper::row).list();
    return mapper.mapAll(rows).stream().findFirst();
  }

  @Override public void delete(Post post) { deleteById(post.getId()); }

  @Override
  public void deleteById(String id) {
    database.sql("delete from %s where post_id = :id".formatted(postTable)).param("id", id).update();
  }

  @Override
  public void deleteAll(Iterable<Post> posts) {
    var ids = new ArrayList<String>();
    posts.forEach(post -> ids.add(post.getId()));
    if (!ids.isEmpty()) {
      database.sql("delete from %s where post_id in (:ids)".formatted(postTable))
          .param("ids", ids).update();
    }
  }

  @Override public long count() { return countWhere("true", Map.of()); }

  @Override
  public List<Post> findByAccountIdOrderByCreatedOnDesc(String accountId) {
    return findByAccountIdOrderByCreatedOnDesc(accountId, Pageable.unpaged());
  }

  @Override
  public List<Post> findByAccountIdOrderByCreatedOnDesc(String accountId, Pageable pageable) {
    return fetch("account_id = :accountId", Map.of("accountId", accountId),
        "created_on desc, post_id desc", pageable);
  }

  @Override
  public Page<Post> findAll(Pageable pageable) {
    return page("true", Map.of(), order(pageable.getSort()), pageable);
  }

  @Override
  public List<Post> findByRootIdOrderByCreatedOnAsc(String rootId) {
    return fetch("root_post_id = :rootId", Map.of("rootId", rootId),
        "created_on asc, post_id asc", Pageable.unpaged());
  }

  @Override
  public List<Post> findByExpiresOnLessThanEqual(Instant cutoff, Pageable pageable) {
    return fetch("expires_on <= :cutoff", Map.of("cutoff", timestamp(cutoff)),
        "expires_on asc, post_id asc", pageable);
  }

  @Override
  public long countByExpiresOnAfter(Instant cutoff) {
    return countWhere("expires_on > :cutoff", Map.of("cutoff", timestamp(cutoff)));
  }

  @Override
  public Page<Post> findByExpiresOnAfter(Instant cutoff, Pageable pageable) {
    return page("expires_on > :cutoff", Map.of("cutoff", timestamp(cutoff)),
        order(pageable.getSort()), pageable);
  }

  @Override
  public List<Post> findByExpiresOnIsNull(Pageable pageable) {
    return fetch("expires_on is null", Map.of(), order(pageable.getSort()), pageable);
  }

  @Override
  public long countByAccountIdAndParentIdIsNull(String accountId) {
    return countWhere("account_id = :accountId and parent_post_id is null",
        Map.of("accountId", accountId));
  }

  @Override
  public long countByAccountIdAndParentIdIsNotNull(String accountId) {
    return countWhere("account_id = :accountId and parent_post_id is not null",
        Map.of("accountId", accountId));
  }

  @Override
  public List<Post> findFederationEligibleAfter(Instant createdOn, String postId, int limit) {
    var where = new StringBuilder("federation_outbound_eligible = true");
    var parameters = new java.util.HashMap<String, Object>();
    if (createdOn != null && postId != null) {
      where.append(" and (created_on > :createdOn or (created_on = :createdOn and post_id > :postId))");
      parameters.put("createdOn", timestamp(createdOn));
      parameters.put("postId", postId);
    }
    return fetch(where.toString(), parameters, "created_on asc, post_id asc", 0, limit);
  }

  @Override
  public List<Post> findFederationOutboxPage(
      String accountId, Instant createdOn, String postId, int limit, Instant expiresAfter) {
    var where = new StringBuilder("""
        account_id = :accountId and federation_outbound_eligible = true
          and expires_on > :expiresAfter and created_on is not null
        """);
    var parameters = new java.util.HashMap<String, Object>();
    parameters.put("accountId", accountId);
    parameters.put("expiresAfter", timestamp(expiresAfter));
    if (createdOn != null && postId != null) {
      where.append(" and (created_on < :createdOn or (created_on = :createdOn and post_id < :postId))");
      parameters.put("createdOn", timestamp(createdOn));
      parameters.put("postId", postId);
    }
    return fetch(where.toString(), parameters, "created_on desc, post_id desc", 0, limit);
  }

  private List<Post> fetch(
      String where, Map<String, ?> parameters, String order, Pageable pageable) {
    return pageable.isPaged()
        ? fetch(where, parameters, order, Math.toIntExact(pageable.getOffset()), pageable.getPageSize())
        : fetch(where, parameters, order, null, null);
  }

  private List<Post> fetch(
      String where, Map<String, ?> parameters, String order, Integer offset, Integer limit) {
    var sql = "select * from %s where %s order by %s".formatted(postTable, where, order)
        + (limit == null ? "" : " limit :limit offset :offset");
    var statement = database.sql(sql);
    for (var entry : parameters.entrySet()) statement.param(entry.getKey(), entry.getValue());
    if (limit != null) statement.param("limit", limit).param("offset", offset);
    return mapper.mapAll(statement.query(mapper::row).list());
  }

  private Page<Post> page(
      String where, Map<String, ?> parameters, String order, Pageable pageable) {
    return new PageImpl<>(fetch(where, parameters, order, pageable), pageable,
        countWhere(where, parameters));
  }

  private long countWhere(String where, Map<String, ?> parameters) {
    var statement = database.sql("select count(*) from %s where %s".formatted(postTable, where));
    for (var entry : parameters.entrySet()) statement.param(entry.getKey(), entry.getValue());
    return statement.query(Long.class).single();
  }

  private static String order(Sort sort) {
    var fields = new ArrayList<String>();
    for (var item : sort) {
      var column = switch (item.getProperty()) {
        case "id" -> "post_id";
        case "createdOn" -> "created_on";
        case "expiresOn" -> "expires_on";
        case "lastExtendedOn" -> "last_extended_on";
        default -> throw new IllegalArgumentException(
            "Unsupported post sort property: " + item.getProperty());
      };
      fields.add(column + (item.isAscending() ? " asc" : " desc"));
    }
    if (fields.isEmpty()) fields.add("post_id asc");
    if (fields.stream().noneMatch(value -> value.startsWith("post_id "))) fields.add("post_id asc");
    return String.join(", ", fields);
  }

  private void deleteChildren(String table, String id) {
    database.sql("delete from %s where post_id = :id".formatted(table)).param("id", id).update();
  }

  private static MapSqlParameterSource parameters(Post post) {
    return new MapSqlParameterSource().addValue("id", post.getId())
        .addValue("accountId", post.getAccountId()).addValue("text", post.getText())
        .addValue("rootId", post.getRootId()).addValue("parentId", post.getParentId(), Types.VARCHAR)
        .addValue("level", post.getLevel() == null ? 0 : post.getLevel())
        .addValue("createdOn", timestamp(post.getCreatedOn()))
        .addValue("updatedOn", timestamp(post.getLastUpdatedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("editedOn", timestamp(post.getEditedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("expiresOn", timestamp(post.getExpiresOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("federationEligible", post.isFederationOutboundEligible())
        .addValue("extendedOn", timestamp(post.getLastExtendedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("likes", post.getLikesCount() == null ? 0 : post.getLikesCount())
        .addValue("replyLikes", post.getThreadReplyLikesCount() == null
            ? 0 : post.getThreadReplyLikesCount())
        .addValue("replies", post.getThreadReplyCount() == null ? 0 : post.getThreadReplyCount());
  }

  static java.time.OffsetDateTime timestamp(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }
}
