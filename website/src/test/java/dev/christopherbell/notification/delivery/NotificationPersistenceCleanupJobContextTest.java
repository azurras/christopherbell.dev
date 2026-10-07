package dev.christopherbell.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class NotificationPersistenceCleanupJobContextTest {

  @Test
  void springSelectsTheProductionDependencyConstructor() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(NotificationFanoutPort.class, () -> mock(NotificationFanoutPort.class));
      context.registerBean(Clock.class, Clock::systemUTC);
      context.register(NotificationPersistenceCleanupJob.class);

      context.refresh();

      assertThat(context.getBean(NotificationPersistenceCleanupJob.class)).isNotNull();
    }
  }
}
