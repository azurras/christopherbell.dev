package dev.christopherbell.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class NotificationPersistenceCleanupJobContextTest {

  @Test
  void springSelectsTheProductionDependencyConstructor() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(NotificationFanoutPort.class, () -> mock(NotificationFanoutPort.class));
      context.register(NotificationPersistenceCleanupJob.class);

      context.refresh();

      assertThat(context.getBean(NotificationPersistenceCleanupJob.class)).isNotNull();
    }
  }
}
