package dev.christopherbell.federation.outbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import dev.christopherbell.federation.configuration.FederationOutboundProperties.ControlledPeer;
import dev.christopherbell.federation.configuration.FederationProperties;
import dev.christopherbell.federation.identity.FederationRequestSigner;
import dev.christopherbell.post.model.Post;

/** Serializes, signs, re-resolves, and sends one canonical activity. */
final class DefaultFederationActivityDeliveryGateway implements FederationActivityDeliveryGateway {
  private final FederationActivityFactory activities;
  private final ObjectMapper objectMapper;
  private final FederationRequestSigner signer;
  private final FederationPeerAddressPolicy addressPolicy;
  private final FederationOutboundHttpClient client;
  private final BrowserSecurityProperties browserSecurity;
  private final FederationProperties properties;

  DefaultFederationActivityDeliveryGateway(
      FederationActivityFactory activities,
      ObjectMapper objectMapper,
      FederationRequestSigner signer,
      FederationPeerAddressPolicy addressPolicy,
      FederationOutboundHttpClient client,
      BrowserSecurityProperties browserSecurity,
      FederationProperties properties
  ) {
    this.activities = activities;
    this.objectMapper = objectMapper;
    this.signer = signer;
    this.addressPolicy = addressPolicy;
    this.client = client;
    this.browserSecurity = browserSecurity;
    this.properties = properties;
  }

  @Override
  public FederationDeliveryResult deliver(Account account, Post post, ControlledPeer peer) {
    byte[] body;
    try {
      body = objectMapper.writeValueAsBytes(
          activities.create(account.getFederationIdentity().actorId(), post));
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Federation activity serialization failed", failure);
    }
    var signed = signer.sign(account, peer.inbox(), body);
    var target = addressPolicy.validateAndResolve(
        peer,
        browserSecurity.publicBaseUrl(),
        properties.outbound().developmentLoopbackEnabled());
    return client.post(target, signed);
  }
}
