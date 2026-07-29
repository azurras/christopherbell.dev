package dev.christopherbell.federation.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.federation.outbound.SignedFederationRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class FederationRequestSignerTest {
  private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");
  private static final String ACTOR = "https://www.christopherbell.dev/ap/users/chris";
  private static final String KEY_ID = ACTOR + "#main-key";

  @Test
  void signsTheExactBodyDigestAndCanonicalRequestFields() throws Exception {
    KeyPair keyPair = rsaKeyPair();
    byte[] decrypted = keyPair.getPrivate().getEncoded();
    byte[] originalPrivateKey = decrypted.clone();
    var signer = signer((accountId, identity) -> decrypted);
    byte[] body = "{\"type\":\"Create\"}".getBytes(StandardCharsets.UTF_8);
    var inbox = URI.create("https://[2606:4700:4700::1111]:8443/inbox?shared=true");

    SignedFederationRequest signed = signer.sign(account(), inbox, body);
    body[0] = 'X';

    String expectedDate = "Tue, 28 Jul 2026 12:00:00 GMT";
    String expectedDigest = "SHA-256=" + Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(
            "{\"type\":\"Create\"}".getBytes(StandardCharsets.UTF_8)));
    String expectedSigningString = "(request-target): post /inbox?shared=true\n"
        + "host: [2606:4700:4700::1111]:8443\n"
        + "date: " + expectedDate + "\n"
        + "digest: " + expectedDigest;
    var verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(keyPair.getPublic());
    verifier.update(expectedSigningString.getBytes(StandardCharsets.US_ASCII));

    assertThat(signed.headers().get("Date")).isEqualTo(expectedDate);
    assertThat(signed.headers().get("Digest")).isEqualTo(expectedDigest);
    assertThat(signed.headers().get("Content-Type")).isEqualTo("application/activity+json");
    assertThat(signed.body()).containsExactly(
        "{\"type\":\"Create\"}".getBytes(StandardCharsets.UTF_8));
    assertThat(verifier.verify(Base64.getDecoder().decode(
        quotedParameter(signed.headers().get("Signature"), "signature")))).isTrue();
    assertThat(quotedParameter(signed.headers().get("Signature"), "keyId")).isEqualTo(KEY_ID);
    assertThat(decrypted).containsOnly((byte) 0);
    assertThat(Arrays.equals(originalPrivateKey, new byte[originalPrivateKey.length])).isFalse();
  }

  @Test
  void clearsDecryptedBytesWhenPrivateKeyParsingFails() {
    byte[] invalidPrivateKey = new byte[] {1, 2, 3, 4};
    var signer = signer((accountId, identity) -> invalidPrivateKey);

    assertThatThrownBy(() -> signer.sign(
        account(), URI.create("https://social.example/inbox"), new byte[] {1}))
        .isInstanceOf(FederationRequestSigningException.class);
    assertThat(invalidPrivateKey).containsOnly((byte) 0);
  }

  @Test
  void mapsAnUnavailableDecryptedKeyToTheSafeSigningFailure() {
    var signer = signer((accountId, identity) -> null);

    assertThatThrownBy(() -> signer.sign(
        account(), URI.create("https://social.example/inbox"), new byte[] {1}))
        .isInstanceOf(FederationRequestSigningException.class)
        .hasMessage("Federation signing key is unavailable");
  }

  private static FederationRequestSigner signer(FederationPrivateKeyDecryptor decryptor) {
    return new FederationRequestSigner(
        decryptor, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static Account account() {
    var identity = new FederationIdentity(
        ACTOR,
        KEY_ID,
        "-----BEGIN PUBLIC KEY-----\npublic\n-----END PUBLIC KEY-----",
        new EncryptedPrivateKey(new byte[12], new byte[16]),
        1,
        NOW.minusSeconds(60));
    return Account.builder()
        .id("account-123")
        .federationEnabled(true)
        .federationIdentity(identity)
        .build();
  }

  private static KeyPair rsaKeyPair() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static String quotedParameter(String header, String name) {
    var matcher = Pattern.compile("(?:^|,)" + Pattern.quote(name) + "=\\\"([^\\\"]+)\\\"")
        .matcher(header);
    if (!matcher.find()) {
      throw new AssertionError("Missing signature parameter: " + name);
    }
    return matcher.group(1);
  }
}
