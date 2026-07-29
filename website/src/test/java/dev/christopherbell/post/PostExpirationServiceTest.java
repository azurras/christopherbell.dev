package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.post.expiration.PostExpirationService;
import dev.christopherbell.post.model.Post;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PostExpirationServiceTest {
  @Mock private PostRepository postRepository;

  @Test
  void purgeExpiredPosts_whenNoPostsNeedWork_doesNotLogStartOrCompletion(CapturedOutput output) {
    var service = new PostExpirationService(postRepository, true);
    when(postRepository.findByExpiresOnIsNull()).thenReturn(List.of());
    when(postRepository.findByExpiresOnLessThanEqual(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

    service.purgeExpiredPosts();

    assertFalse(output.getOut().contains("Post expiration cleanup job started."));
    assertFalse(output.getOut().contains("Post expiration cleanup job completed."));
  }

  @Test
  void confirmedReplyImmediatelyRevivesTheRootAndSynchronizesTheThread() throws Exception {
    var service = new PostExpirationService(postRepository, true);
    var createdOn = Instant.parse("2026-07-29T01:00:00Z");
    var extendedOn = Instant.parse("2026-07-29T03:00:00Z");
    var root = Post.builder()
        .id("root")
        .rootId("root")
        .createdOn(createdOn)
        .expiresOn(createdOn.plus(Duration.ofHours(24)))
        .build();
    var reply = Post.builder()
        .id("reply")
        .rootId("root")
        .parentId("root")
        .expiresOn(root.getExpiresOn())
        .build();
    when(postRepository.findById(eq("root"))).thenReturn(java.util.Optional.of(root));
    when(postRepository.findByRootIdOrderByCreatedOnAsc(eq("root")))
        .thenReturn(List.of(root, reply));

    service.refreshThreadRootExpirationForNewReply(reply, extendedOn);

    assertEquals(extendedOn, root.getLastExtendedOn());
    assertEquals(createdOn.plus(Duration.ofHours(48)), root.getExpiresOn());
    assertEquals(root.getExpiresOn(), reply.getExpiresOn());
    verify(postRepository, atLeastOnce()).save(eq(root));
    verify(postRepository, atLeastOnce()).save(eq(reply));
  }
}
