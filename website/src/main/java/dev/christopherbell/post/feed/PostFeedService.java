package dev.christopherbell.post.feed;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.trust.AccountTrustService;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.post.PostMapper;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.expiration.PostExpirationService;
import dev.christopherbell.post.hide.HiddenPostThreadService;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostDetail;
import dev.christopherbell.post.model.PostFeedItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Owns global, following, user, and current-user feed reads. */
@RequiredArgsConstructor
@Service
public class PostFeedService {
  private static final int MIN_FEED_LIMIT = 1;
  private static final int MAX_FEED_LIMIT = 100;

  private final PostRepository postRepository;
  private final AccountRepository accountRepository;
  private final PostMapper postMapper;
  private final PostExpirationService postExpirationService;
  private final AccountTrustService accountTrustService;
  private final HiddenPostThreadService hiddenPostThreadService;

  public List<PostDetail> getMyPosts(String selfId) throws ResourceNotFoundException {
    accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));

    var posts = postRepository.findByAccountIdOrderByCreatedOnDesc(selfId);
    posts.forEach(postExpirationService::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !postExpirationService.isExpired(p))
        .map(postMapper::toDetail)
        .toList();
  }

  public List<PostFeedItem> getMyFeed(String selfId, Instant before, int limit) throws ResourceNotFoundException {
    var account = accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));

    Pageable page = newFeedPage(limit);
    List<Post> posts = before != null
        ? postRepository.findByAccountIdAndCreatedOnLessThanOrderByCreatedOnDesc(selfId, before, page)
        : postRepository.findByAccountIdOrderByCreatedOnDesc(selfId, page);

    posts.forEach(postExpirationService::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !postExpirationService.isExpired(p))
        .map(p -> toFeedItem(p, account.getUsername(), selfId))
        .toList();
  }

  public List<PostFeedItem> getFollowingFeed(String selfId, Instant before, int limit)
      throws ResourceNotFoundException {
    var self = accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));

    var followingIds = self.getFollowingIds() == null ? List.<String>of() : self.getFollowingIds().stream().toList();
    if (followingIds.isEmpty()) {
      return List.of();
    }

    Pageable page = newFeedPage(limit);
    List<Post> posts = before != null
        ? postRepository.findByAccountIdInAndCreatedOnLessThanOrderByCreatedOnDesc(followingIds, before, page)
        : postRepository.findByAccountIdInOrderByCreatedOnDesc(followingIds, page);

    var idToUser = usernamesByAccountId(followingIds);
    var hidden = hiddenFor(selfId);
    posts.forEach(postExpirationService::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !postExpirationService.isExpired(p))
        .filter(p -> isVisibleToSelf(p, hidden))
        .map(p -> toFeedItem(p, idToUser.get(p.getAccountId()), selfId))
        .toList();
  }

  public List<PostDetail> getPostsByAccountId(String accountId)
      throws InvalidRequestException, ResourceNotFoundException {
    if (accountId == null || accountId.isBlank()) {
      throw new InvalidRequestException("Account id cannot be null or blank.");
    }
    accountRepository
        .findById(accountId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", accountId)));

    var posts = postRepository.findByAccountIdOrderByCreatedOnDesc(accountId);
    posts.forEach(postExpirationService::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !postExpirationService.isExpired(p))
        .map(postMapper::toDetail)
        .toList();
  }

  public List<PostFeedItem> getGlobalFeed(Instant before, int limit, String selfId) {
    Pageable page = newFeedPage(limit);
    List<Post> posts = before != null
        ? postRepository.findByCreatedOnLessThanOrderByCreatedOnDesc(before, page)
        : postRepository.findAll(page).getContent();

    var authorIds = posts.stream().map(Post::getAccountId).distinct().toList();
    var idToUser = usernamesByAccountId(authorIds);
    var hidden = hiddenFor(selfId);
    posts.forEach(postExpirationService::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !postExpirationService.isExpired(p))
        .filter(p -> isVisibleToSelf(p, hidden))
        .map(p -> toFeedItem(p, idToUser.get(p.getAccountId()), selfId))
        .toList();
  }

  public List<PostFeedItem> getUserFeed(String username, Instant before, int limit, String selfId)
      throws ResourceNotFoundException {
    var sanitized = UsernameSanitizer.sanitize(username);
    var account = accountRepository.findByUsername(sanitized)
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with username %s not found.", sanitized)));

    Pageable page = newFeedPage(limit);
    List<Post> posts = before != null
        ? postRepository.findByAccountIdAndCreatedOnLessThanOrderByCreatedOnDesc(account.getId(), before, page)
        : postRepository.findByAccountIdOrderByCreatedOnDesc(account.getId(), page);

    posts.forEach(postExpirationService::ensureExpirationSet);
    var hidden = hiddenFor(selfId);
    return posts.stream()
        .filter(p -> !postExpirationService.isExpired(p))
        .filter(p -> isVisibleToSelf(p, hidden))
        .map(p -> toFeedItem(p, account.getUsername(), selfId))
        .toList();
  }

  private Pageable newFeedPage(int requestedLimit) {
    int pageSize = Math.max(MIN_FEED_LIMIT, Math.min(requestedLimit, MAX_FEED_LIMIT));
    return PageRequest.of(0, pageSize, Sort.by(Sort.Direction.DESC, "createdOn"));
  }

  private Map<String, String> usernamesByAccountId(List<String> accountIds) {
    return accountRepository.findAllById(accountIds).stream()
        .collect(Collectors.toMap(Account::getId, Account::getUsername));
  }

  private HiddenFeedState hiddenFor(String selfId) {
    if (selfId == null || selfId.isBlank()) {
      return new HiddenFeedState(Set.of(), Set.of());
    }
    return new HiddenFeedState(
        accountTrustService.hiddenAccountIdsForSelf(),
        hiddenPostThreadService.hiddenRootIdsForSelf());
  }

  private boolean isVisibleToSelf(Post post, HiddenFeedState hidden) {
    var rootId = post.getRootId() == null ? post.getId() : post.getRootId();
    return !hidden.accountIds().contains(post.getAccountId()) && !hidden.rootIds().contains(rootId);
  }

  private record HiddenFeedState(Set<String> accountIds, Set<String> rootIds) {}

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
