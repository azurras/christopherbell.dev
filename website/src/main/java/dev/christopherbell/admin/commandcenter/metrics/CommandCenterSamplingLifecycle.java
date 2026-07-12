package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/** Registers host sampling only on the command center's private scheduler. */
@Component
public final class CommandCenterSamplingLifecycle implements InitializingBean {
  private final TaskScheduler scheduler;
  private final CommandCenterMetricsService metricsService;
  private final CommandCenterProperties properties;
  private final AtomicBoolean registered = new AtomicBoolean();

  public CommandCenterSamplingLifecycle(
      @Qualifier("commandCenterMetricsScheduler") TaskScheduler scheduler,
      CommandCenterMetricsService metricsService,
      CommandCenterProperties properties) {
    this.scheduler = scheduler;
    this.metricsService = metricsService;
    this.properties = properties;
  }

  @Override
  public void afterPropertiesSet() {
    if (registered.compareAndSet(false, true)) {
      scheduler.scheduleWithFixedDelay(metricsService::collect, properties.getSampleInterval());
    }
  }
}
