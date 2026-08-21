package dev.christopherbell.federation.identity;

import dev.christopherbell.federation.api.EncryptedPrivateKey;
import dev.christopherbell.federation.api.FederationIdentity;

import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/** Creates one stable local ActivityPub actor and encrypted RSA signing identity. */
public final class FederationIdentityFactory {
  private static final String KEY_ALGORITHM = "RSA";
  private static final int RSA_KEY_BITS = 2048;
  private static final int INITIAL_KEY_VERSION = 1;
  private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,50}$");

  private final String publicOrigin;
  private final FederationIdentityCryptography cryptography;
  private final Clock clock;

  public FederationIdentityFactory(
      BrowserSecurityProperties browserSecurity,
      FederationIdentityCryptography cryptography,
      Clock clock
  ) {
    this.publicOrigin = Objects.requireNonNull(browserSecurity, "browserSecurity")
        .publicBaseUrl()
        .toString();
    this.cryptography = Objects.requireNonNull(cryptography, "cryptography");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public FederationIdentity create(String accountId, String username) {
    requireText(accountId, "account ID");
    if (username == null || !USERNAME.matcher(username).matches()) {
      throw new IllegalArgumentException("Federation username is invalid");
    }

    String actorId = publicOrigin + "/ap/users/" + username;
    String keyId = actorId + "#main-key";
    byte[] privateKey = null;
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
      generator.initialize(RSA_KEY_BITS);
      var keyPair = generator.generateKeyPair();
      privateKey = keyPair.getPrivate().getEncoded();
      EncryptedPrivateKey encryptedPrivateKey = cryptography.encrypt(
          accountId, actorId, keyId, INITIAL_KEY_VERSION, privateKey);
      return new FederationIdentity(
          actorId,
          keyId,
          FederationPemCodec.encodePublicKey(keyPair.getPublic().getEncoded()),
          encryptedPrivateKey,
          INITIAL_KEY_VERSION,
          Instant.now(clock));
    } catch (GeneralSecurityException exception) {
      throw new FederationIdentityCreationException(
          "Federation RSA identity generation failed", exception);
    } finally {
      if (privateKey != null) {
        Arrays.fill(privateKey, (byte) 0);
      }
    }
  }

  private static void requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Federation " + label + " must not be blank");
    }
  }
}
