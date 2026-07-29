package dev.christopherbell.federation.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import dev.christopherbell.federation.configuration.FederationProperties;
import dev.christopherbell.federation.identity.EncryptedPrivateKey;
import dev.christopherbell.federation.identity.FederationIdentity;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.post.PostRepository;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FederationDiscoveryServiceTest {
  @Mock private AccountRepository accounts;
  @Mock private PostRepository posts;

  private FederationDiscoveryService discovery;

  @BeforeEach
  void setUp() {
    discovery = service(true);
  }

  @Test
  void disabledDiscoveryUsesTheSameNotFoundBoundaryForEveryPublicLookup() {
    discovery = service(false);

    assertThrows(ResourceNotFoundException.class,
        () -> discovery.webFinger("acct:chris@www.christopherbell.dev"));
    assertThrows(ResourceNotFoundException.class, discovery::nodeInfoDiscovery);
    assertThrows(ResourceNotFoundException.class, discovery::nodeInfo);
    assertThrows(ResourceNotFoundException.class, () -> discovery.actor("chris"));
  }

  @Test
  void webFingerResolvesOnlyAnExactLocalActiveConsentedAccount() throws Exception {
    var account = account();
    when(accounts.findByUsernameIgnoreCaseAndStatusAndFederationEnabledTrue(
        "chris", AccountStatus.ACTIVE)).thenReturn(Optional.of(account));

    var document = discovery.webFinger("acct:chris@WWW.CHRISTOPHERBELL.DEV");

    assertThat(document.subject()).isEqualTo("acct:Chris@www.christopherbell.dev");
    assertThat(document.aliases()).containsExactly(account.getFederationIdentity().actorId());
    assertThat(document.links()).singleElement().satisfies(link -> {
      assertThat(link.rel()).isEqualTo("self");
      assertThat(link.type()).isEqualTo("application/activity+json");
      assertThat(link.href()).isEqualTo(account.getFederationIdentity().actorId());
    });
  }

  @Test
  void malformedAndForeignWebFingerResourcesDoNotQueryAccounts() {
    assertThrows(InvalidRequestException.class, () -> discovery.webFinger("not-an-acct"));
    assertThrows(ResourceNotFoundException.class,
        () -> discovery.webFinger("acct:chris@example.com"));
  }

  @Test
  void actorProjectsOnlyPublicFieldsFromAValidatedStoredIdentity() throws Exception {
    var account = account();
    when(accounts.findByUsernameIgnoreCaseAndStatusAndFederationEnabledTrue(
        "Chris", AccountStatus.ACTIVE)).thenReturn(Optional.of(account));

    var actor = discovery.actor("Chris");

    assertThat(actor.context()).contains("https://www.w3.org/ns/activitystreams");
    assertThat(actor.id()).isEqualTo(account.getFederationIdentity().actorId());
    assertThat(actor.type()).isEqualTo("Person");
    assertThat(actor.preferredUsername()).isEqualTo("Chris");
    assertThat(actor.inbox()).endsWith("/inbox");
    assertThat(actor.outbox()).endsWith("/outbox");
    assertThat(actor.publicKey().publicKeyPem()).startsWith("-----BEGIN PUBLIC KEY-----");
  }

  @Test
  void missingIdentityUsesTheSameNotFoundResponseAsAMissingAccount() {
    var account = account();
    account.setFederationIdentity(null);
    when(accounts.findByUsernameIgnoreCaseAndStatusAndFederationEnabledTrue(
        "Chris", AccountStatus.ACTIVE)).thenReturn(Optional.of(account));

    assertThrows(ResourceNotFoundException.class, () -> discovery.actor("Chris"));
  }

  @Test
  void nodeInfoUsesBoundedAggregateCountsAndConfiguredSoftwareMetadata() throws Exception {
    when(accounts.countByStatus(AccountStatus.ACTIVE)).thenReturn(12L);
    when(posts.count()).thenReturn(34L);

    var document = discovery.nodeInfo();

    assertThat(document.version()).isEqualTo("2.1");
    assertThat(document.software().name()).isEqualTo("christopherbell.dev");
    assertThat(document.protocols()).containsExactly("activitypub");
    assertThat(document.usage().users().total()).isEqualTo(12L);
    assertThat(document.usage().localPosts()).isEqualTo(34L);
  }

  private FederationDiscoveryService service(boolean enabled) {
    byte[] secret = new byte[32];
    var properties = new FederationProperties(
        enabled,
        false,
        false,
        "christopherbell.dev",
        "test-version",
        enabled ? Base64.getEncoder().encodeToString(secret) : null,
        null);
    return new FederationDiscoveryService(
        properties,
        new BrowserSecurityProperties(
            URI.create("https://www.christopherbell.dev"), true, true),
        accounts,
        posts);
  }

  private static Account account() {
    var actorId = "https://www.christopherbell.dev/ap/users/Chris";
    return Account.builder()
        .id("account-123")
        .username("Chris")
        .status(AccountStatus.ACTIVE)
        .federationEnabled(true)
        .federationIdentity(new FederationIdentity(
            actorId,
            actorId + "#main-key",
            "-----BEGIN PUBLIC KEY-----\npublic\n-----END PUBLIC KEY-----",
            new EncryptedPrivateKey(new byte[12], new byte[16]),
            1,
            Instant.parse("2026-07-28T12:00:00Z")))
        .build();
  }
}
