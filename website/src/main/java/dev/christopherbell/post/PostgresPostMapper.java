package dev.christopherbell.post;

import static dev.christopherbell.persistence.jooq.social.Tables.POST_EDIT_AUDIT;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_LINK_PREVIEW;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_TOPIC;

import dev.christopherbell.persistence.jooq.social.tables.records.PostRecord;
import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.post.editing.PostEditAuditEvent;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostLinkPreview;
import dev.christopherbell.post.model.PostTopic;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;

/** Reconstructs one PostgreSQL post aggregate for adapters that query post rows. */
@PostgresPersistenceSupport
public final class PostgresPostMapper {
  private PostgresPostMapper() {}

  public static Post map(DSLContext context, PostRecord record) {
    return mapAll(context, List.of(record)).getFirst();
  }

  public static List<Post> mapAll(DSLContext context, List<PostRecord> records) {
    if (records.isEmpty()) return List.of();
    var postIds = records.stream().map(PostRecord::getPostId).toList();
    Map<String, List<PostEditAuditEvent>> audits = initializedLists(postIds);
    Map<String, List<PostTopic>> topics = initializedLists(postIds);
    Map<String, List<PostLinkPreview>> previews = initializedLists(postIds);
    context.selectFrom(POST_EDIT_AUDIT)
        .where(POST_EDIT_AUDIT.POST_ID.in(postIds))
        .orderBy(POST_EDIT_AUDIT.POST_ID.asc(), POST_EDIT_AUDIT.ORDINAL.asc())
        .forEach(row -> audits.get(row.getPostId()).add(new PostEditAuditEvent(
            row.getEditorAccountId(), row.getBeforeText(), row.getAfterText(),
            row.getEditedOn().toInstant())));
    context.selectFrom(POST_TOPIC)
        .where(POST_TOPIC.POST_ID.in(postIds))
        .orderBy(POST_TOPIC.POST_ID.asc(), POST_TOPIC.ORDINAL.asc())
        .forEach(row -> topics.get(row.getPostId())
            .add(new PostTopic(row.getCanonical(), row.getDisplay())));
    context.selectFrom(POST_LINK_PREVIEW)
        .where(POST_LINK_PREVIEW.POST_ID.in(postIds))
        .orderBy(POST_LINK_PREVIEW.POST_ID.asc(), POST_LINK_PREVIEW.ORDINAL.asc())
        .forEach(row -> previews.get(row.getPostId()).add(PostLinkPreview.builder()
            .url(row.getUrl()).domain(row.getDomainName()).title(row.getTitle())
            .description(row.getDescription()).imageUrl(row.getImageUrl()).build()));
    return records.stream().map(record -> map(
        record, audits.get(record.getPostId()), topics.get(record.getPostId()),
        previews.get(record.getPostId()))).toList();
  }

  private static Post map(
      PostRecord record,
      List<PostEditAuditEvent> audit,
      List<PostTopic> topics,
      List<PostLinkPreview> previews) {
    return Post.builder()
        .id(record.getPostId())
        .accountId(record.getAccountId())
        .text(record.getPostText())
        .rootId(record.getRootPostId())
        .parentId(record.getParentPostId())
        .level(record.getThreadLevel())
        .createdOn(record.getCreatedOn().toInstant())
        .lastUpdatedOn(instant(record.getLastUpdatedOn()))
        .editedOn(instant(record.getEditedOn()))
        .editAudit(audit)
        .expiresOn(instant(record.getExpiresOn()))
        .federationOutboundEligible(record.getFederationOutboundEligible())
        .lastExtendedOn(instant(record.getLastExtendedOn()))
        .topics(topics)
        .likesCount(record.getLikesCount())
        .threadReplyLikesCount(record.getThreadReplyLikesCount())
        .threadReplyCount(record.getThreadReplyCount())
        .linkPreviews(previews)
        .build();
  }

  private static <T> Map<String, List<T>> initializedLists(List<String> ids) {
    var result = new LinkedHashMap<String, List<T>>();
    ids.forEach(id -> result.put(id, new ArrayList<>()));
    return result;
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
