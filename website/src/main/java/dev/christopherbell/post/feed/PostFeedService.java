package dev.christopherbell.post.feed;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.follow.AccountFollowStore;
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
import dev.christopherbell.pagination.StableCursor;
import dev.christopherbell.pagination.StableCursorCodec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private static final int MAX_FEED_LIMIT = 100;
  private static final Pageable LEGACY_HISTORY_PAGE = PageRequest.of(
      0,
      MAX_FEED_LIMIT,
      Sort.by(Sort.Order.desc("createdOn"), Sort.Order.desc("_id")));

  private final PostRepository postRepository;
  private final AccountRepository accountRepository;
  private final AccountFollowStore follows;
  private final PostMapper postMapper;
  private final PostExpirationService postExpirationService;
  private final AccountTrustService accountTrustService;
  private final HiddenPostThreadService hiddenPostThreadService;
  private final PostFeedQueryRepository postFeedQueryRepository;
  private final StableCursorCodec cursorCodec;
  private final PostFeedItemAssembler feedItems;

  public List<PostDetail> getMyPosts(String selfId) throws ResourceNotFoundException {
    accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));

    var posts = postRepository.findByAccountIdOrderByCreatedOnDesc(selfId, LEGACY_HISTORY_PAGE);
    return posts.stream()
        .filter(p -> !postExpirationService.isExpired(p))
        .map(postMapper::toDetail)
        .toList();
  }

  /** Returns one stable page of post details authored by the current user. */
  public PostDetailPage getMyPostsPage(String selfId, String cursor, int size)
      throws InvalidRequestException, ResourceNotFoundException {
    return getPostsByAccountPage(selfId, cursor, size);
  }

  public List<PostFeedItem> getMyFeed(String selfId, Instant before, int limit) throws ResourceNotFoundException {
    var account = accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));

    var hidden = hiddenFor(selfId);
    var slice = postFeedQueryRepository.account(
        selfId, legacyBoundary(before), limit, visibility(hidden));
    return feedItems.assemble(
        slice.posts(), Map.of(selfId, account.getUsername()), selfId);
  }

  /** Returns one stable page of posts authored by the current user. */
  public PostFeedPage getMyFeedPage(String selfId, String cursor, int size)
      throws InvalidRequestException, ResourceNotFoundException {
    var account = accountRepository.findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with id %s not found.", selfId)));
    var hidden = hiddenFor(selfId);
    var slice = postFeedQueryRepository.account(
        selfId, cursorCodec.decode(cursor), size, visibility(hidden));
    return mapPage(slice, Map.of(selfId, account.getUsername()), selfId);
  }

  public List<PostFeedItem> getFollowingFeed(String selfId, Instant before, int limit)
      throws ResourceNotFoundException {
    var self = accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));

    var hidden = hiddenFor(selfId);
    var slice = postFeedQueryRepository.following(
        self.getId(), legacyBoundary(before), limit, visibility(hidden));
    var authorIds = slice.posts().stream().map(Post::getAccountId).distinct().toList();
    return feedItems.assemble(slice.posts(), usernamesByAccountId(authorIds), selfId);
  }

  /** Returns one stable page from the current user's followed accounts. */
  public PostFeedPage getFollowingFeedPage(String selfId, String cursor, int size)
      throws InvalidRequestException, ResourceNotFoundException {
    accountRepository.findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with id %s not found.", selfId)));
    if (follows.countFollowing(selfId) == 0) {
      return new PostFeedPage(List.of(), null);
    }
    var hidden = hiddenFor(selfId);
    var slice = postFeedQueryRepository.following(
        selfId, cursorCodec.decode(cursor), size, visibility(hidden));
    var authorIds = slice.posts().stream().map(Post::getAccountId).distinct().toList();
    return mapPage(slice, usernamesByAccountId(authorIds), selfId);
  }

  public List<PostDetail> getPostsByAccountId(String accountId)
      throws InvalidRequestException, ResourceNotFoundException {
    if (accountId == null || accountId.isBlank()) {
      throw new InvalidRequestException("Account id cannot be null or blank.");
    }
    accountRepository
        .findById(accountId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", accountId)));

    var posts = postRepository.findByAccountIdOrderByCreatedOnDesc(accountId, LEGACY_HISTORY_PAGE);
    return posts.stream()
        .filter(p -> !postExpirationService.isExpired(p))
        .map(postMapper::toDetail)
        .toList();
  }

  /** Returns one stable page of post details for an account. */
  public PostDetailPage getPostsByAccountPage(String accountId, String cursor, int size)
      throws InvalidRequestException, ResourceNotFoundException {
    if (accountId == null || accountId.isBlank()) {
      throw new InvalidRequestException("Account id cannot be null or blank.");
    }
    accountRepository.findById(accountId)
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with id %s not found.", accountId)));
    var slice = postFeedQueryRepository.account(
        accountId,
        cursorCodec.decode(cursor),
        size,
        new PostFeedVisibility(Set.of(), Set.of(), postExpirationService.activeCutoff()));
    return new PostDetailPage(
        slice.posts().stream().map(postMapper::toDetail).toList(),
        slice.nextCursor());
  }

  public List<PostFeedItem> getGlobalFeed(Instant before, int limit, String selfId) {
    var hidden = hiddenFor(selfId);
    var slice = postFeedQueryRepository.global(
        legacyBoundary(before), limit, visibility(hidden));
    var authorIds = slice.posts().stream().map(Post::getAccountId).distinct().toList();
    return feedItems.assemble(
        slice.posts(), usernamesByAccountId(authorIds), selfId);
  }

  /** Returns one stable global feed page. */
  public PostFeedPage getGlobalFeedPage(String cursor, int size, String selfId)
      throws InvalidRequestException {
    var hidden = hiddenFor(selfId);
    var slice = postFeedQueryRepository.global(
        cursorCodec.decode(cursor), size, visibility(hidden));
    var authorIds = slice.posts().stream().map(Post::getAccountId).distinct().toList();
    return mapPage(slice, usernamesByAccountId(authorIds), selfId);
  }

  public List<PostFeedItem> getUserFeed(String username, Instant before, int limit, String selfId)
      throws ResourceNotFoundException {
    var sanitized = UsernameSanitizer.sanitize(username);
    var account = accountRepository.findByUsername(sanitized)
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with username %s not found.", sanitized)));

    var hidden = hiddenFor(selfId);
    var slice = postFeedQueryRepository.account(
        account.getId(), legacyBoundary(before), limit, visibility(hidden));
    return feedItems.assemble(
        slice.posts(), Map.of(account.getId(), account.getUsername()), selfId);
  }

  /** Returns one stable public page for a username. */
  public PostFeedPage getUserFeedPage(
      String username,
      String cursor,
      int size,
      String selfId
  ) throws InvalidRequestException, ResourceNotFoundException {
    var sanitized = UsernameSanitizer.sanitize(username);
    var account = accountRepository.findByUsername(sanitized)
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with username %s not found.", sanitized)));
    var hidden = hiddenFor(selfId);
    var slice = postFeedQueryRepository.account(
        account.getId(), cursorCodec.decode(cursor), size, visibility(hidden));
    return mapPage(
        slice, Map.of(account.getId(), account.getUsername()), selfId);
  }

  private Optional<StableCursor> legacyBoundary(Instant before) {
    return before == null
        ? Optional.empty()
        : Optional.of(new StableCursor(before, ""));
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

  private PostFeedPage mapPage(
      PostFeedSlice slice,
      Map<String, String> usernames,
      String selfId
  ) {
    var items = feedItems.assemble(slice.posts(), usernames, selfId);
    return new PostFeedPage(items, slice.nextCursor());
  }

  private PostFeedVisibility visibility(HiddenFeedState hidden) {
    return new PostFeedVisibility(
        hidden.accountIds(), hidden.rootIds(), postExpirationService.activeCutoff());
  }

  private record HiddenFeedState(Set<String> accountIds, Set<String> rootIds) {}

}
