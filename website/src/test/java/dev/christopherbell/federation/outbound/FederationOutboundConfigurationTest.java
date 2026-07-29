package dev.christopherbell.federation.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import dev.christopherbell.federation.configuration.FederationOutboundProperties;
import dev.christopherbell.federation.configuration.FederationProperties;
import dev.christopherbell.federation.identity.FederationRequestSigner;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

class FederationOutboundConfigurationTest {
  @Test
  void enabledOutboundGraphUsesTheApplicationJsonMapper() {
    new ApplicationContextRunner()
        .withUserConfiguration(FederationOutboundConfiguration.class, Dependencies.class)
        .withPropertyValues("app.federation.outbound-enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(FederationActivityDeliveryGateway.class);
        });
  }

  @Configuration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean
    FederationProperties federationProperties() {
      var properties = mock(FederationProperties.class);
      when(properties.outbound()).thenReturn(new FederationOutboundProperties(
          null,
          List.of(),
          Duration.ofSeconds(1),
          Duration.ofSeconds(2),
          Duration.ofSeconds(1),
          Duration.ofSeconds(2),
          3,
          10,
          false));
      return properties;
    }

    @Bean
    FederationActivityFactory federationActivityFactory() {
      return mock(FederationActivityFactory.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    FederationRequestSigner federationRequestSigner() {
      return mock(FederationRequestSigner.class);
    }

    @Bean
    BrowserSecurityProperties browserSecurityProperties() {
      return new BrowserSecurityProperties(URI.create("https://example.com"), true, true);
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
