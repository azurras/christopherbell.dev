package dev.christopherbell.post.thread;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.expiration.PostExpirationService;
import dev.christopherbell.post.feed.PostFeedItemAssembler;
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
  private final PostFeedItemAssembler feedItems;

  public PostFeedItem getPostById(String id, String selfId) throws ResourceNotFoundException {
    var post = postRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Post with id %s not found.", id)));
    postExpirationService.ensureActive(post);
    var author = accountRepository.findById(post.getAccountId())
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", post.getAccountId())));
    return feedItems.single(post, author.getUsername(), selfId);
  }

  public List<PostFeedItem> getThread(String id, String selfId) throws ResourceNotFoundException {
    var post = postRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Post with id %s not found.", id)));
    postExpirationService.ensureActive(post);
    var rootId = post.getRootId() != null ? post.getRootId() : post.getId();
    var posts = postRepository.findByRootIdOrderByCreatedOnAsc(rootId);
    var authorIds = posts.stream().map(Post::getAccountId).distinct().toList();
    var idToUser = usernamesByAccountId(authorIds);
    var active = posts.stream().filter(p -> !postExpirationService.isExpired(p)).toList();
    return feedItems.assemble(active, idToUser, selfId);
  }

  private Map<String, String> usernamesByAccountId(List<String> accountIds) {
    return accountRepository.findAllById(accountIds).stream()
        .collect(Collectors.toMap(Account::getId, Account::getUsername));
  }

}
