package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

import dev.christopherbell.post.expiration.PostExpirationService;
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
}
