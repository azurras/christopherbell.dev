package dev.christopherbell.post.interaction;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.notification.delivery.NotificationDeliveryService;
import dev.christopherbell.post.PostMapper;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.abuse.NewAccountVoidMutationLimiter;
import dev.christopherbell.post.abuse.VoidMutationKind;
import dev.christopherbell.post.expiration.PostExpirationService;
import dev.christopherbell.post.feed.PostFeedItemAssembler;
import dev.christopherbell.post.like.PostLikeStore;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostDetail;
import dev.christopherbell.post.model.PostFeedItem;
import java.time.Clock;
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
  private final PostLikeStore postLikes;
  private final PostFeedItemAssembler feedItems;
  private final NewAccountVoidMutationLimiter mutationLimiter;
  private final Clock clock;

  public PostFeedItem toggleLike(String postId, String selfId)
      throws ResourceNotFoundException {
    return setLiked(postId, selfId, !postLikes.exists(postId, selfId));
  }

  /** Applies one retry-safe desired like state. */
  public PostFeedItem setLiked(String postId, String selfId, boolean desiredLiked)
      throws ResourceNotFoundException {
    var post = postRepository.findById(postId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Post with id %s not found.", postId)));
    var now = clock.instant();
    postExpirationService.ensureActive(post);
    var threadRoot = postExpirationService.activeThreadRootForReply(post);
    var author = accountRepository.findById(post.getAccountId())
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with id %s not found.", post.getAccountId())));
    Account actor = desiredLiked
        ? accountRepository.findById(selfId)
            .orElseThrow(() -> new ResourceNotFoundException(
                String.format("Account with id %s not found.", selfId)))
        : null;
    var transition = desiredLiked
        ? postLikes.like(postId, selfId, now)
        : postLikes.unlike(postId, selfId);
    if (transition.created()) {
      try {
        mutationLimiter.require(actor, VoidMutationKind.KEEP_ALIVE);
      } catch (RuntimeException exception) {
        postLikes.unlike(postId, selfId);
        throw exception;
      }
    }
    var updated = postExpirationService.applyLikeTransition(post, threadRoot, transition.delta(), now);
    if (transition.created() && !selfId.equals(author.getId())) {
      notificationDeliveryService.createPostLikeNotification(updated, actor, author);
    }
    return feedItems.single(updated, author.getUsername(), selfId);
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

}
