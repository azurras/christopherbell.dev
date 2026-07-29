package dev.christopherbell.post.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.christopherbell.post.editing.PostEditAuditEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB document representing a tweet‑like post authored by an account.
 *
 * <p>Posts are stored in the separate {@code posts} collection and linked back
 * to the owning account via {@link #accountId}. Content is modeled as short
 * text suitable for micro‑blogging.</p>
 */
@AllArgsConstructor
@Builder
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@CompoundIndexes({
    @CompoundIndex(name = "post_account_created_id_desc", def = "{'accountId': 1, 'createdOn': -1, '_id': -1}"),
    @CompoundIndex(name = "post_created_id_desc", def = "{'createdOn': -1, '_id': -1}"),
    @CompoundIndex(name = "post_root_created_asc", def = "{'rootId': 1, 'createdOn': 1}"),
    @CompoundIndex(name = "post_parent", def = "{'parentId': 1}"),
    @CompoundIndex(name = "post_expires", def = "{'expiresOn': 1}"),
    @CompoundIndex(name = "post_account_parent", def = "{'accountId': 1, 'parentId': 1}")
})
@Document("posts")
public class Post {
  private final String type = "post";

  /** Unique post identifier (UUID string). */
  @Id private String id;

  /** Owning account's identifier. */
  private String accountId;
  // Tweet-like short message body (trimmed, <= 280 chars)
  private String text;

  /** Identifier of the root post in the thread (self for top-level posts). */
  private String rootId;
  /** Identifier of the direct parent post (null for top-level posts). */
  private String parentId;
  /** Depth within the thread: 0 for root, 1 for a reply, etc. */
  private Integer level;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  @CreatedDate
  private Instant createdOn;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  @LastModifiedDate
  private Instant lastUpdatedOn;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  private Instant editedOn;

  @Builder.Default
  private List<PostEditAuditEvent> editAudit = new ArrayList<>();

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  private Instant expiresOn;

  /** True only when this post was explicitly eligible for outbound federation at creation. */
  private Boolean federationOutboundEligible;

  /** Treats historical missing/null values as explicitly ineligible. */
  public boolean isFederationOutboundEligible() {
    return Boolean.TRUE.equals(federationOutboundEligible);
  }

  /** Most recent confirmed interaction that extended this root post's lifespan. */
  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  private Instant lastExtendedOn;

  /** Normalized hashtags extracted from {@link #text} for public discovery. */
  @Builder.Default
  private List<PostTopic> topics = new ArrayList<>();

  // Expiration extension counters. Display engagement is derived from edge collections.
  private Integer likesCount;
  /** Reply likes that extend this thread root without changing its own like count. */
  private Integer threadReplyLikesCount;
  /** Number of replies in this root thread. */
  private Integer threadReplyCount;
  /** Rich previews resolved from web links in {@link #text}. */
  private List<PostLinkPreview> linkPreviews;
}
