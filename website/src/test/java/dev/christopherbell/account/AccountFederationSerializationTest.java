package dev.christopherbell.account;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.federation.identity.EncryptedPrivateKey;
import dev.christopherbell.federation.identity.FederationIdentity;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AccountFederationSerializationTest {

  @Test
  void accountJsonNeverContainsFederationPrivateIdentityMaterial() throws Exception {
    var identity = new FederationIdentity(
        "https://www.christopherbell.dev/ap/users/chris",
        "https://www.christopherbell.dev/ap/users/chris#main-key",
        "-----BEGIN PUBLIC KEY-----\npublic\n-----END PUBLIC KEY-----",
        new EncryptedPrivateKey(new byte[12], new byte[16]),
        1,
        Instant.parse("2026-07-28T12:00:00Z"));
    var account = Account.builder()
        .id("account-123")
        .username("chris")
        .federationEnabled(true)
        .federationIdentity(identity)
        .build();

    String json = new ObjectMapper().writeValueAsString(account);

    assertThat(json)
        .contains("\"federationEnabled\":true")
        .doesNotContain("federationIdentity")
        .doesNotContain("encryptedPrivateKey")
        .doesNotContain("ciphertext")
        .doesNotContain("main-key");
  }
}
