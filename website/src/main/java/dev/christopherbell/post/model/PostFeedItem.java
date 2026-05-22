package dev.christopherbell.post.model;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * Feed DTO representing a post and basic thread/like metadata.
 *
 * <p>Components:</p>
 * - id/accountId/username: identity and author
 * - text: content (already sanitized at storage time)
 * - rootId/parentId/level: thread hierarchy (level 0=root)
 * - likesCount/liked: aggregate likes and current user's like state
 * - createdOn/lastUpdatedOn: timestamps
 */
@Builder
public record PostFeedItem(
    String id,
    String accountId,
    String username,
    String text,
    List<PostLinkPreview> linkPreviews,
    String rootId,
    String parentId,
    Integer level,
    Integer likesCount,
    Boolean liked,
    Integer replyCount,
    Instant createdOn,
    Instant lastUpdatedOn,
    Instant expiresOn
) {}
