package dev.christopherbell.post;

import static dev.christopherbell.persistence.jooq.social.Tables.POST_EDIT_AUDIT;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_LINK_PREVIEW;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_TOPIC;

import dev.christopherbell.persistence.jooq.social.tables.records.PostRecord;
import dev.christopherbell.post.editing.PostEditAuditEvent;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostLinkPreview;
import dev.christopherbell.post.model.PostTopic;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;

/** Reconstructs one PostgreSQL post aggregate for adapters that query post rows. */
public final class PostgresPostMapper {
  private PostgresPostMapper() {}

  public static Post map(DSLContext context, PostRecord record) {
    var audit = context.selectFrom(POST_EDIT_AUDIT)
        .where(POST_EDIT_AUDIT.POST_ID.eq(record.getPostId()))
        .orderBy(POST_EDIT_AUDIT.ORDINAL.asc())
        .fetch(row -> new PostEditAuditEvent(
            row.getEditorAccountId(), row.getBeforeText(), row.getAfterText(),
            row.getEditedOn().toInstant()));
    var topics = context.selectFrom(POST_TOPIC)
        .where(POST_TOPIC.POST_ID.eq(record.getPostId()))
        .orderBy(POST_TOPIC.ORDINAL.asc())
        .fetch(row -> new PostTopic(row.getCanonical(), row.getDisplay()));
    var previews = context.selectFrom(POST_LINK_PREVIEW)
        .where(POST_LINK_PREVIEW.POST_ID.eq(record.getPostId()))
        .orderBy(POST_LINK_PREVIEW.ORDINAL.asc())
        .fetch(row -> PostLinkPreview.builder()
            .url(row.getUrl())
            .domain(row.getDomainName())
            .title(row.getTitle())
            .description(row.getDescription())
            .imageUrl(row.getImageUrl())
            .build());
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

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
