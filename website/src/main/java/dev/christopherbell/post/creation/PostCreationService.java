package dev.christopherbell.post.creation;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.notification.delivery.NotificationDeliveryService;
import dev.christopherbell.post.PostMapper;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.expiration.PostExpirationService;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostCreateRequest;
import dev.christopherbell.post.model.PostDetail;
import dev.christopherbell.post.preview.PostLinkPreviewService;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Owns creating root posts and replies. */
@RequiredArgsConstructor
@Service
public class PostCreationService {
  private static final int MAX_TEXT_LENGTH = 280;

  private final PostRepository postRepository;
  private final AccountRepository accountRepository;
  private final PostMapper postMapper;
  private final NotificationDeliveryService notificationDeliveryService;
  private final PostLinkPreviewService postLinkPreviewService;
  private final PostExpirationService postExpirationService;

  /** Creates a post or reply for the resolved current account id. */
  public PostDetail createPost(PostCreateRequest request, Supplier<String> selfIdSupplier)
      throws InvalidRequestException, ResourceNotFoundException {
    if (request == null || request.text() == null || request.text().isBlank()) {
      throw new InvalidRequestException("Post text cannot be null or blank.");
    }

    var text = request.text().trim();
    if (text.length() > MAX_TEXT_LENGTH) {
      throw new InvalidRequestException("Post text exceeds 280 characters.");
    }
    var selfId = selfIdSupplier.get();
    var account = accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));
    ensureActiveAuthor(account);
    var now = Instant.now();

    String parentId = request.parentId();
    String rootId;
    Integer level;
    Instant inheritedReplyExpiration = null;
    Account parentAuthor = null;
    if (parentId != null && !parentId.isBlank()) {
      var parent = postRepository.findById(parentId)
          .orElseThrow(() -> new ResourceNotFoundException(
              String.format("Parent post with id %s not found.", parentId)));
      postExpirationService.ensureActive(parent);
      if (!account.getId().equals(parent.getAccountId())) {
        parentAuthor = accountRepository.findById(parent.getAccountId()).orElse(null);
      }
      rootId = parent.getRootId() != null ? parent.getRootId() : parent.getId();
      level = (parent.getLevel() != null ? parent.getLevel() : 0) + 1;
      inheritedReplyExpiration = postExpirationService.rootExpirationFor(parent, rootId);
    } else {
      rootId = null;
      level = 0;
    }

    var newId = UUID.randomUUID().toString();
    if (rootId == null) {
      rootId = newId;
    }

    var post = Post.builder()
        .id(newId)
        .accountId(account.getId())
        .text(text)
        .rootId(rootId)
        .parentId(parentId)
        .level(level)
        .likedBy(new HashSet<>())
        .likesCount(0)
        .createdOn(now)
        .lastUpdatedOn(now)
        .expiresOn(postExpirationService.expirationForNewPost(now, inheritedReplyExpiration))
        .linkPreviews(postLinkPreviewService.resolveForText(text))
        .build();

    var saved = postRepository.save(post);
    postExpirationService.refreshThreadRootExpirationForNewReply(saved);
    notificationDeliveryService.createMentionNotifications(saved, account);
    if (parentAuthor != null) {
      notificationDeliveryService.createPostCommentNotification(saved, account, parentAuthor);
    }
    return postMapper.toDetail(saved);
  }

  private static void ensureActiveAuthor(Account account) throws InvalidRequestException {
    if (account.getStatus() == AccountStatus.SUSPENDED) {
      throw new InvalidRequestException("Suspended accounts cannot create posts.");
    }
  }
}
