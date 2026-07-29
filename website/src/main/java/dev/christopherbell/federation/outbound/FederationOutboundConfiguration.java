package dev.christopherbell.federation.outbound;

import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import dev.christopherbell.federation.configuration.FederationProperties;
import dev.christopherbell.federation.identity.FederationRequestSigner;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Wires remote federation effects only when outbound delivery is explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.federation.outbound-enabled", havingValue = "true")
class FederationOutboundConfiguration {
  @Bean
  FederationPeerAddressPolicy federationPeerAddressPolicy() {
    return new FederationPeerAddressPolicy();
  }

  @Bean
  FederationOutboundHttpClient federationOutboundHttpClient(
      FederationProperties properties, Clock clock) {
    return new FederationOutboundHttpClient(
        properties.outbound().connectTimeout(), properties.outbound().requestTimeout(), clock);
  }

  @Bean
  FederationActivityDeliveryGateway federationActivityDeliveryGateway(
      FederationActivityFactory activities,
      ObjectMapper objectMapper,
      FederationRequestSigner signer,
      FederationPeerAddressPolicy addressPolicy,
      FederationOutboundHttpClient client,
      BrowserSecurityProperties browserSecurity,
      FederationProperties properties
  ) {
    return new DefaultFederationActivityDeliveryGateway(
        activities, objectMapper, signer, addressPolicy, client, browserSecurity, properties);
  }
}
