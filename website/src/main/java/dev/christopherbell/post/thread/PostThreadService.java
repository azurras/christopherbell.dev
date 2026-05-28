package dev.christopherbell.post.thread;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.expiration.PostExpirationService;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostFeedItem;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Owns single-post and thread reads. */
@RequiredArgsConstructor
@Service
public class PostThreadService {
  private final PostRepository postRepository;
  private final AccountRepository accountRepository;
  private final PostExpirationService postExpirationService;

  public PostFeedItem getPostById(String id, String selfId) throws ResourceNotFoundException {
    var post = postRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Post with id %s not found.", id)));
    postExpirationService.ensureActive(post);
    var author = accountRepository.findById(post.getAccountId())
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", post.getAccountId())));
    postExpirationService.ensureExpirationSet(post);
    return toFeedItem(post, author.getUsername(), selfId);
  }

  public List<PostFeedItem> getThread(String id, String selfId) throws ResourceNotFoundException {
    var post = postRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Post with id %s not found.", id)));
    postExpirationService.ensureActive(post);
    var rootId = post.getRootId() != null ? post.getRootId() : post.getId();
    var posts = postRepository.findByRootIdOrderByCreatedOnAsc(rootId);
    var authorIds = posts.stream().map(Post::getAccountId).distinct().toList();
    var idToUser = usernamesByAccountId(authorIds);
    posts.forEach(postExpirationService::ensureExpirationSet);
    return posts.stream()
        .filter(p -> !postExpirationService.isExpired(p))
        .map(p -> toFeedItem(p, idToUser.get(p.getAccountId()), selfId))
        .toList();
  }

  private Map<String, String> usernamesByAccountId(List<String> accountIds) {
    return accountRepository.findAllById(accountIds).stream()
        .collect(Collectors.toMap(Account::getId, Account::getUsername));
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
