package dev.christopherbell.post.hide;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.PostRepository;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Owns per-account hidden thread roots. */
@RequiredArgsConstructor
@Service
public class HiddenPostThreadService {
  private final HiddenPostThreadRepository hiddenPostThreadRepository;
  private final PostRepository postRepository;
  private final PermissionService permissionService;

  /** Hides the root thread for the selected post id. */
  public void hideThread(String postId) throws InvalidRequestException, ResourceNotFoundException {
    if (postId == null || postId.isBlank()) {
      throw new InvalidRequestException("Post id is required.");
    }
    var post = postRepository.findById(postId)
        .orElseThrow(() -> new ResourceNotFoundException("Post not found."));
    var rootPostId = post.getRootId() == null ? post.getId() : post.getRootId();
    var accountId = permissionService.getSelfId();
    hiddenPostThreadRepository.findByAccountIdAndRootPostId(accountId, rootPostId)
        .orElseGet(() -> hiddenPostThreadRepository.save(HiddenPostThread.builder()
            .accountId(accountId)
            .rootPostId(rootPostId)
            .build()));
  }

  /** Clears a hidden root thread for the current account. */
  public void unhideThread(String rootPostId) throws InvalidRequestException {
    if (rootPostId == null || rootPostId.isBlank()) {
      throw new InvalidRequestException("Root post id is required.");
    }
    hiddenPostThreadRepository.deleteByAccountIdAndRootPostId(permissionService.getSelfId(), rootPostId);
  }

  /** Hidden thread root ids for the current account. */
  public Set<String> hiddenRootIdsForSelf() {
    return hiddenPostThreadRepository.findByAccountId(permissionService.getSelfId()).stream()
        .map(HiddenPostThread::getRootPostId)
        .collect(Collectors.toSet());
  }
}
