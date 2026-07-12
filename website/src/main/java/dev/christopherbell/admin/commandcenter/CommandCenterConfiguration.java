package dev.christopherbell.admin.commandcenter;

import dev.christopherbell.admin.commandcenter.action.CommandExecutor;
import dev.christopherbell.admin.commandcenter.action.SimulatedCommandExecutor;
import dev.christopherbell.admin.commandcenter.action.WindowsCommandExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import oshi.spi.SystemInfoFactory;
import oshi.spi.SystemInfoProvider;

/** Creates shared host integrations used by command-center providers. */
@Configuration
public class CommandCenterConfiguration {

  /**
   * Creates the platform-specific OSHI provider once for reuse by all metric collectors.
   *
   * @return the available system information provider selected by OSHI
   */
  @Bean
  public SystemInfoProvider systemInfoProvider() {
    return SystemInfoFactory.create();
  }

  /** Selects the safe simulated executor unless Windows mode is explicitly configured. */
  @Bean
  @ConditionalOnBean(CommandCenterProperties.class)
  public CommandExecutor commandExecutor(CommandCenterProperties properties) {
    return switch (properties.getActions().getMode()) {
      case SIMULATED -> new SimulatedCommandExecutor();
      case WINDOWS -> new WindowsCommandExecutor(properties);
    };
  }

  /** Schedules website restarts after the acceptance response has had time to flush. */
  @Bean
  public TaskScheduler commandCenterActionScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("command-center-action-");
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
    return scheduler;
  }
}
