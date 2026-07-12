package dev.christopherbell.admin.commandcenter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
}
