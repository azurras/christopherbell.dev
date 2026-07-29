package dev.christopherbell.federation.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FederationIdentityCryptographyTest {
  private static final String ACCOUNT_ID = "account-123";
  private static final String ACTOR_ID = "https://www.christopherbell.dev/ap/users/chris";
  private static final String KEY_ID = ACTOR_ID + "#main-key";
  private static final int KEY_VERSION = 1;

  @Test
  void encryptedPrivateKeyRoundTripsOnlyWithItsAuthenticatedIdentity() {
    var cryptography = new FederationIdentityCryptography(secret((byte) 1));
    byte[] privateKey = "private-key-material".getBytes(StandardCharsets.UTF_8);
    var identity = identity(cryptography.encrypt(
        ACCOUNT_ID, ACTOR_ID, KEY_ID, KEY_VERSION, privateKey));

    assertArrayEquals(privateKey, cryptography.decrypt(ACCOUNT_ID, identity));
  }

  @Test
  void eachEncryptionUsesAFreshNonce() {
    var cryptography = new FederationIdentityCryptography(secret((byte) 2));
    byte[] privateKey = "same-private-key".getBytes(StandardCharsets.UTF_8);

    var first = cryptography.encrypt(ACCOUNT_ID, ACTOR_ID, KEY_ID, KEY_VERSION, privateKey);
    var second = cryptography.encrypt(ACCOUNT_ID, ACTOR_ID, KEY_ID, KEY_VERSION, privateKey);

    assertFalse(Arrays.equals(first.nonce(), second.nonce()));
  }

  @Test
  void accountBindingPreventsCiphertextFromMovingBetweenAccounts() {
    var cryptography = new FederationIdentityCryptography(secret((byte) 3));
    var identity = identity(cryptography.encrypt(
        ACCOUNT_ID, ACTOR_ID, KEY_ID, KEY_VERSION, new byte[] {1, 2, 3}));

    assertThrows(FederationIdentityCryptographyException.class,
        () -> cryptography.decrypt("different-account", identity));
  }

  @Test
  void aDifferentSecretCannotDecryptTheIdentity() {
    var encryptor = new FederationIdentityCryptography(secret((byte) 4));
    var decryptor = new FederationIdentityCryptography(secret((byte) 5));
    var identity = identity(encryptor.encrypt(
        ACCOUNT_ID, ACTOR_ID, KEY_ID, KEY_VERSION, new byte[] {4, 5, 6}));

    assertThrows(FederationIdentityCryptographyException.class,
        () -> decryptor.decrypt(ACCOUNT_ID, identity));
  }

  @Test
  void corruptCiphertextFailsClosed() {
    var cryptography = new FederationIdentityCryptography(secret((byte) 6));
    var encrypted = cryptography.encrypt(
        ACCOUNT_ID, ACTOR_ID, KEY_ID, KEY_VERSION, new byte[] {7, 8, 9});
    byte[] corruptCiphertext = encrypted.ciphertext();
    corruptCiphertext[0] ^= 1;
    var identity = identity(new EncryptedPrivateKey(encrypted.nonce(), corruptCiphertext));

    assertThrows(FederationIdentityCryptographyException.class,
        () -> cryptography.decrypt(ACCOUNT_ID, identity));
  }

  @Test
  void aes256RequiresExactlyThirtyTwoSecretBytes() {
    assertThrows(IllegalArgumentException.class,
        () -> new FederationIdentityCryptography(new byte[31]));
  }

  private static FederationIdentity identity(EncryptedPrivateKey encryptedPrivateKey) {
    return new FederationIdentity(
        ACTOR_ID,
        KEY_ID,
        "-----BEGIN PUBLIC KEY-----\npublic\n-----END PUBLIC KEY-----",
        encryptedPrivateKey,
        KEY_VERSION,
        Instant.parse("2026-07-28T12:00:00Z"));
  }

  private static byte[] secret(byte value) {
    byte[] secret = new byte[32];
    Arrays.fill(secret, value);
    return secret;
  }
}
