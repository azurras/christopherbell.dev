package dev.christopherbell.federation.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import dev.christopherbell.federation.identity.FederationRequestSigner;
import java.net.URI;
import java.time.Clock;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class FederationConfigurationTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(FederationConfiguration.class, Dependencies.class)
      .withPropertyValues(
          "app.federation.inbound-enabled=false",
          "app.federation.outbound-enabled=false",
          "app.federation.software-name=christopherbell.dev",
          "app.federation.software-version=test");

  @Test
  void disabledDiscoveryDoesNotConstructSigningServices() {
    contextRunner
        .withPropertyValues("app.federation.discovery-enabled=false")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(FederationRequestSigner.class);
        });
  }

  @Test
  void enabledDiscoveryConstructsTheAccountBoundSigner() {
    String secret = Base64.getEncoder().encodeToString(new byte[32]);

    contextRunner
        .withPropertyValues(
            "app.federation.discovery-enabled=true",
            "app.federation.key-encryption-secret=" + secret)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(FederationRequestSigner.class);
        });
  }

  @Configuration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean
    BrowserSecurityProperties browserSecurityProperties() {
      return new BrowserSecurityProperties(
          URI.create("https://www.christopherbell.dev"), true, true);
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
