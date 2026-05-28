package dev.christopherbell.post.interaction;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.notification.delivery.NotificationDeliveryService;
import dev.christopherbell.post.PostMapper;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.expiration.PostExpirationService;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostDetail;
import dev.christopherbell.post.model.PostFeedItem;
import java.time.Instant;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Owns likes and delete behavior for posts. */
@RequiredArgsConstructor
@Service
public class PostInteractionService {
  private final PostRepository postRepository;
  private final AccountRepository accountRepository;
  private final PostMapper postMapper;
  private final NotificationDeliveryService notificationDeliveryService;
  private final PostExpirationService postExpirationService;

  public PostFeedItem toggleLike(String postId, String selfId)
      throws ResourceNotFoundException {
    var post = postRepository.findById(postId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Post with id %s not found.", postId)));
    postExpirationService.ensureActive(post);
    var threadRoot = postExpirationService.activeThreadRootForReply(post);
    if (post.getLikedBy() == null) {
      post.setLikedBy(new HashSet<>());
    }
    boolean liked;
    if (post.getLikedBy().contains(selfId)) {
      post.getLikedBy().remove(selfId);
      post.setLikesCount(Math.max(0, (post.getLikesCount() == null ? 0 : post.getLikesCount()) - 1));
      liked = false;
    } else {
      post.getLikedBy().add(selfId);
      post.setLikesCount((post.getLikesCount() == null ? 0 : post.getLikesCount()) + 1);
      liked = true;
    }
    post.setLastUpdatedOn(Instant.now());
    postExpirationService.refreshExpiration(post);
    postRepository.save(post);
    postExpirationService.synchronizeReplyExpirations(post);
    postExpirationService.refreshThreadRootExpiration(threadRoot, liked ? 1 : -1);
    var author = accountRepository.findById(post.getAccountId())
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", post.getAccountId())));
    if (liked && !selfId.equals(author.getId())) {
      accountRepository.findById(selfId)
          .ifPresent(actor -> notificationDeliveryService.createPostLikeNotification(post, actor, author));
    }
    return toFeedItem(post, author.getUsername(), liked ? selfId : null);
  }

  public PostDetail deletePost(String postId, String selfId, boolean isAdmin)
      throws ResourceNotFoundException, InvalidRequestException {
    var post = postRepository
        .findById(postId)
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Post with id %s not found.", postId)));

    boolean isOwner = post.getAccountId() != null && post.getAccountId().equals(selfId);
    if (!isOwner && !isAdmin) {
      throw new InvalidRequestException("Not authorized to delete this post.");
    }

    postExpirationService.deletePostTree(post);
    return postMapper.toDetail(post);
  }

  private PostFeedItem toFeedItem(Post post, String username, String currentUserId) {
    return PostFeedItem.builder()
        .id(post.getId())
        .accountId(post.getAccountId())
        .username(username)
        .text(post.getText())
        .linkPreviews(post.getLinkPreviews())
        .rootId(post.getRootId())
        .parentId(post.getParentId())
        .level(post.getLevel())
        .likesCount(post.getLikesCount())
        .liked(currentUserId != null
            && post.getLikedBy() != null
            && post.getLikedBy().contains(currentUserId))
        .replyCount((int) postRepository.countByParentId(post.getId()))
        .createdOn(post.getCreatedOn())
        .lastUpdatedOn(post.getLastUpdatedOn())
        .expiresOn(post.getExpiresOn())
        .build();
  }
}
