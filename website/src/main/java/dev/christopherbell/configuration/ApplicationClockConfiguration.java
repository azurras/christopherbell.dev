package dev.christopherbell.configuration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the application-wide UTC time source independently of the persistence backend. */
@Configuration(proxyBeanMethods = false)
public class ApplicationClockConfiguration {

  @Bean
  public Clock applicationClock() {
    return Clock.systemUTC();
  }
}
