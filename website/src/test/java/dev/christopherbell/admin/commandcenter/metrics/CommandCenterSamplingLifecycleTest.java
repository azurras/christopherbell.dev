package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

class CommandCenterSamplingLifecycleTest {
  @Test
  void registersCollectorOnlyOnItsQualifiedPrivateSchedulerContract() throws Exception {
    var scheduler = mock(TaskScheduler.class);
    var metrics = mock(CommandCenterMetricsService.class);
    var properties = new CommandCenterProperties();
    properties.setSampleInterval(Duration.ofSeconds(5));
    var lifecycle = new CommandCenterSamplingLifecycle(scheduler, metrics, properties);

    lifecycle.afterPropertiesSet();
    lifecycle.afterPropertiesSet();

    verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofSeconds(5)));
    assertThat(CommandCenterMetricsService.class.getMethod("collect").getAnnotation(Scheduled.class))
        .isNull();
  }
}
