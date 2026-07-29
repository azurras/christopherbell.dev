package dev.christopherbell.federation.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.federation.configuration.FederationOutboundProperties;
import dev.christopherbell.federation.configuration.FederationProperties;
import dev.christopherbell.federation.identity.EncryptedPrivateKey;
import dev.christopherbell.federation.identity.FederationIdentity;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class FederationPublicationPolicyTest {

  @Test
  void marksOnlyActiveConsentedEnrolledAuthorsWhileOutboundIsEnabled() {
    var enabled = new FederationPublicationPolicy(properties(true));

    assertThat(enabled.eligibleAtCreation(account(AccountStatus.ACTIVE, true, true))).isTrue();
    assertThat(enabled.eligibleAtCreation(account(AccountStatus.ACTIVE, false, true))).isFalse();
    assertThat(enabled.eligibleAtCreation(account(AccountStatus.ACTIVE, true, false))).isFalse();
    assertThat(enabled.eligibleAtCreation(account(AccountStatus.SUSPENDED, true, true))).isFalse();
    assertThat(new FederationPublicationPolicy(properties(false))
        .eligibleAtCreation(account(AccountStatus.ACTIVE, true, true))).isFalse();
  }

  private static Account account(AccountStatus status, boolean consented, boolean enrolled) {
    return Account.builder()
        .id("account-123")
        .status(status)
        .federationEnabled(consented)
        .federationIdentity(enrolled ? identity() : null)
        .build();
  }

  private static FederationIdentity identity() {
    String actor = "https://www.christopherbell.dev/ap/users/chris";
    return new FederationIdentity(
        actor,
        actor + "#main-key",
        "-----BEGIN PUBLIC KEY-----\npublic\n-----END PUBLIC KEY-----",
        new EncryptedPrivateKey(new byte[12], new byte[16]),
        1,
        Instant.parse("2026-07-28T00:00:00Z"));
  }

  private static FederationProperties properties(boolean outboundEnabled) {
    var outbound = new FederationOutboundProperties(
        Instant.parse("2026-07-28T00:00:00Z"),
        List.of(new FederationOutboundProperties.ControlledPeer(
            "peer", URI.create("https://social.example/inbox"))),
        Duration.ofSeconds(3),
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        Duration.ofHours(6),
        6,
        10,
        false);
    return new FederationProperties(
        true,
        false,
        outboundEnabled,
        "site",
        "test",
        Base64.getEncoder().encodeToString(new byte[32]),
        outbound);
  }
}
