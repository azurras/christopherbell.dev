package dev.christopherbell.federation.identity;

import dev.christopherbell.federation.api.FederationIdentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import java.net.URI;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FederationIdentityFactoryTest {
  private static final Instant CREATED_ON = Instant.parse("2026-07-28T15:30:00Z");

  @Test
  void createsAStableActorIdentityFromTheCanonicalBrowserOrigin() throws Exception {
    var cryptography = new FederationIdentityCryptography(new byte[32]);
    var factory = new FederationIdentityFactory(
        new BrowserSecurityProperties(
            URI.create("https://www.christopherbell.dev"), true, true),
        cryptography,
        Clock.fixed(CREATED_ON, ZoneOffset.UTC));

    FederationIdentity identity = factory.create("account-123", "Christopher.Bell");

    assertEquals(
        "https://www.christopherbell.dev/ap/users/Christopher.Bell",
        identity.actorId());
    assertEquals(identity.actorId() + "#main-key", identity.keyId());
    assertEquals(1, identity.keyVersion());
    assertEquals(CREATED_ON, identity.createdOn());
    assertTrue(identity.publicKeyPem().startsWith("-----BEGIN PUBLIC KEY-----\n"));

    byte[] encodedPrivateKey = cryptography.decrypt("account-123", identity);
    var privateKey = KeyFactory.getInstance("RSA")
        .generatePrivate(new PKCS8EncodedKeySpec(encodedPrivateKey));
    assertEquals("RSA", privateKey.getAlgorithm());
  }
}
