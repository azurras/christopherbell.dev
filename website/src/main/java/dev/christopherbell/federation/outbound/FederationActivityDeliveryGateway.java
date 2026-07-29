package dev.christopherbell.federation.outbound;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.federation.configuration.FederationOutboundProperties.ControlledPeer;
import dev.christopherbell.post.model.Post;

/** Performs one fully validated, serialized, signed, and bounded remote delivery attempt. */
interface FederationActivityDeliveryGateway {
  FederationDeliveryResult deliver(Account account, Post post, ControlledPeer peer);
}
