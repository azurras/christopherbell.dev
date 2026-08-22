package dev.christopherbell.post;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.post.editing.PostEditAuditEvent;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostLinkPreview;
import dev.christopherbell.post.model.PostTopic;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reconstructs PostgreSQL post aggregates without generated query-model classes. */
@PostgresPersistenceSupport
public final class PostgresPostMapper {
  private final JdbcClient database;
  private final String auditTable;
  private final String topicTable;
  private final String previewTable;

  public PostgresPostMapper(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    auditTable = schemas.qualifiedTable("social", "post_edit_audit");
    topicTable = schemas.qualifiedTable("social", "post_topic");
    previewTable = schemas.qualifiedTable("social", "post_link_preview");
  }

  public Post map(ResultSet row, int rowNumber) throws SQLException {
    return mapAll(List.of(row(row, rowNumber))).getFirst();
  }

  public PostRow row(ResultSet row, int rowNumber) throws SQLException {
    return new PostRow(
        row.getString("post_id"), row.getString("account_id"), row.getString("post_text"),
        row.getString("root_post_id"), row.getString("parent_post_id"),
        row.getInt("thread_level"), instant(row, "created_on"),
        instant(row, "last_updated_on"), instant(row, "edited_on"),
        instant(row, "expires_on"), row.getBoolean("federation_outbound_eligible"),
        instant(row, "last_extended_on"), row.getInt("likes_count"),
        row.getInt("thread_reply_likes_count"), row.getInt("thread_reply_count"));
  }

  public List<Post> mapAll(List<PostRow> rows) {
    if (rows.isEmpty()) return List.of();
    var ids = rows.stream().map(PostRow::id).toList();
    Map<String, List<PostEditAuditEvent>> audits = initializedLists(ids);
    Map<String, List<PostTopic>> topics = initializedLists(ids);
    Map<String, List<PostLinkPreview>> previews = initializedLists(ids);
    database.sql("""
            select * from %s where post_id in (:ids) order by post_id asc, ordinal asc
            """.formatted(auditTable)).param("ids", ids).query((row, ignored) -> {
          audits.get(row.getString("post_id")).add(new PostEditAuditEvent(
              row.getString("editor_account_id"), row.getString("before_text"),
              row.getString("after_text"), instant(row, "edited_on")));
          return 0;
        }).list();
    database.sql("""
            select * from %s where post_id in (:ids) order by post_id asc, ordinal asc
            """.formatted(topicTable)).param("ids", ids).query((row, ignored) -> {
          topics.get(row.getString("post_id")).add(
              new PostTopic(row.getString("canonical"), row.getString("display")));
          return 0;
        }).list();
    database.sql("""
            select * from %s where post_id in (:ids) order by post_id asc, ordinal asc
            """.formatted(previewTable)).param("ids", ids).query((row, ignored) -> {
          previews.get(row.getString("post_id")).add(PostLinkPreview.builder()
              .url(row.getString("url")).domain(row.getString("domain_name"))
              .title(row.getString("title")).description(row.getString("description"))
              .imageUrl(row.getString("image_url")).build());
          return 0;
        }).list();
    return rows.stream().map(row -> map(
        row, audits.get(row.id()), topics.get(row.id()), previews.get(row.id()))).toList();
  }

  private static Post map(
      PostRow row, List<PostEditAuditEvent> audits,
      List<PostTopic> topics, List<PostLinkPreview> previews) {
    return Post.builder().id(row.id()).accountId(row.accountId()).text(row.text())
        .rootId(row.rootId()).parentId(row.parentId()).level(row.level())
        .createdOn(row.createdOn()).lastUpdatedOn(row.lastUpdatedOn()).editedOn(row.editedOn())
        .editAudit(audits).expiresOn(row.expiresOn())
        .federationOutboundEligible(row.federationOutboundEligible())
        .lastExtendedOn(row.lastExtendedOn()).topics(topics).likesCount(row.likesCount())
        .threadReplyLikesCount(row.threadReplyLikesCount())
        .threadReplyCount(row.threadReplyCount()).linkPreviews(previews).build();
  }

  private static <T> Map<String, List<T>> initializedLists(List<String> ids) {
    var result = new LinkedHashMap<String, List<T>>();
    ids.forEach(id -> result.put(id, new ArrayList<>()));
    return result;
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    var value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  public record PostRow(
      String id, String accountId, String text, String rootId, String parentId, int level,
      Instant createdOn, Instant lastUpdatedOn, Instant editedOn, Instant expiresOn,
      boolean federationOutboundEligible, Instant lastExtendedOn, int likesCount,
      int threadReplyLikesCount, int threadReplyCount) {}
}
