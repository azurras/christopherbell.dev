package dev.christopherbell.federation.configuration;

import dev.christopherbell.federation.identity.FederationIdentityCryptography;
import dev.christopherbell.federation.identity.FederationIdentityFactory;
import dev.christopherbell.federation.identity.FederationRequestSigner;
import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires federation cryptography only when the discovery surface is deliberately enabled. */
@Configuration
@EnableConfigurationProperties(FederationProperties.class)
public class FederationConfiguration {

  @Bean
  @ConditionalOnProperty(name = "app.federation.discovery-enabled", havingValue = "true")
  FederationIdentityCryptography federationIdentityCryptography(FederationProperties properties) {
    return new FederationIdentityCryptography(properties.requiredEncryptionSecret());
  }

  @Bean
  @ConditionalOnProperty(name = "app.federation.discovery-enabled", havingValue = "true")
  FederationIdentityFactory federationIdentityFactory(
      BrowserSecurityProperties browserSecurity,
      FederationIdentityCryptography cryptography,
      Clock clock
  ) {
    return new FederationIdentityFactory(browserSecurity, cryptography, clock);
  }

  @Bean
  @ConditionalOnProperty(name = "app.federation.discovery-enabled", havingValue = "true")
  FederationRequestSigner federationRequestSigner(
      FederationIdentityCryptography cryptography,
      Clock clock
  ) {
    return new FederationRequestSigner(cryptography, clock);
  }
}
