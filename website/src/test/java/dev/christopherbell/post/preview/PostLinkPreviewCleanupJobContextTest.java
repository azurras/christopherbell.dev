package dev.christopherbell.post.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class PostLinkPreviewCleanupJobContextTest {

  @Test
  void springSelectsTheProductionDependencyConstructor() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          PostLinkPreviewCacheRepository.class,
          () -> mock(PostLinkPreviewCacheRepository.class));
      context.register(PostLinkPreviewCleanupJob.class);

      context.refresh();

      assertThat(context.getBean(PostLinkPreviewCleanupJob.class)).isNotNull();
    }
  }
}
