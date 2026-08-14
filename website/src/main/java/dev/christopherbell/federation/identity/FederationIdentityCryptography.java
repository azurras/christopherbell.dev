package dev.christopherbell.federation.identity;

import dev.christopherbell.federation.api.EncryptedPrivateKey;
import dev.christopherbell.federation.api.FederationIdentity;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Seals federation signing keys without exposing algorithm or nonce choices to callers. */
public final class FederationIdentityCryptography implements FederationPrivateKeyDecryptor {
  private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";
  private static final int SECRET_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int AUTHENTICATION_TAG_BITS = 128;

  private final SecretKeySpec secretKey;
  private final SecureRandom secureRandom;

  public FederationIdentityCryptography(byte[] secret) {
    Objects.requireNonNull(secret, "secret");
    if (secret.length != SECRET_BYTES) {
      throw new IllegalArgumentException("Federation encryption secret must be exactly 32 bytes");
    }
    this.secretKey = new SecretKeySpec(secret.clone(), KEY_ALGORITHM);
    this.secureRandom = new SecureRandom();
  }

  public EncryptedPrivateKey encrypt(
      String accountId,
      String actorId,
      String keyId,
      int keyVersion,
      byte[] pkcs8
  ) {
    requireIdentityContext(accountId, actorId, keyId, keyVersion);
    Objects.requireNonNull(pkcs8, "pkcs8");
    if (pkcs8.length == 0) {
      throw new IllegalArgumentException("Federation private key must not be empty");
    }

    byte[] nonce = new byte[NONCE_BYTES];
    secureRandom.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(AUTHENTICATION_TAG_BITS, nonce));
      cipher.updateAAD(authenticatedContext(accountId, actorId, keyId, keyVersion));
      return new EncryptedPrivateKey(nonce, cipher.doFinal(pkcs8));
    } catch (GeneralSecurityException exception) {
      throw new FederationIdentityCryptographyException(
          "Federation private-key encryption failed", exception);
    }
  }

  @Override
  public byte[] decrypt(String accountId, FederationIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    requireIdentityContext(
        accountId, identity.actorId(), identity.keyId(), identity.keyVersion());
    try {
      Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
      cipher.init(
          Cipher.DECRYPT_MODE,
          secretKey,
          new GCMParameterSpec(AUTHENTICATION_TAG_BITS, identity.encryptedPrivateKey().nonce()));
      cipher.updateAAD(authenticatedContext(
          accountId, identity.actorId(), identity.keyId(), identity.keyVersion()));
      return cipher.doFinal(identity.encryptedPrivateKey().ciphertext());
    } catch (GeneralSecurityException exception) {
      throw new FederationIdentityCryptographyException(
          "Federation private-key decryption failed", exception);
    }
  }

  private static byte[] authenticatedContext(
      String accountId,
      String actorId,
      String keyId,
      int keyVersion
  ) {
    try {
      var output = new ByteArrayOutputStream();
      try (var data = new DataOutputStream(output)) {
        writeLengthPrefixed(data, accountId);
        writeLengthPrefixed(data, actorId);
        writeLengthPrefixed(data, keyId);
        data.writeInt(keyVersion);
      }
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to construct federation key context", exception);
    }
  }

  private static void writeLengthPrefixed(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static void requireIdentityContext(
      String accountId,
      String actorId,
      String keyId,
      int keyVersion
  ) {
    requireText(accountId, "account ID");
    requireText(actorId, "actor ID");
    requireText(keyId, "key ID");
    if (keyVersion < 1) {
      throw new IllegalArgumentException("Federation key version must be positive");
    }
  }

  private static void requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Federation " + label + " must not be blank");
    }
  }
}
