package dev.christopherbell.federation.api;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/** Stable public actor identity with private signing material encrypted at rest. */
public record FederationIdentity(
    String actorId,
    String keyId,
    String publicKeyPem,
    EncryptedPrivateKey encryptedPrivateKey,
    int keyVersion,
    Instant createdOn
) {
  private static final String PUBLIC_KEY_PREFIX = "-----BEGIN PUBLIC KEY-----\n";
  private static final String PUBLIC_KEY_SUFFIX = "\n-----END PUBLIC KEY-----";

  public FederationIdentity {
    requireHttpUri(actorId, "actor ID");
    if (!Objects.equals(keyId, actorId + "#main-key")) {
      throw new IllegalArgumentException("Federation key ID must belong to its actor");
    }
    if (publicKeyPem == null
        || !publicKeyPem.startsWith(PUBLIC_KEY_PREFIX)
        || !publicKeyPem.endsWith(PUBLIC_KEY_SUFFIX)) {
      throw new IllegalArgumentException("Federation public key must use PEM encoding");
    }
    Objects.requireNonNull(encryptedPrivateKey, "encryptedPrivateKey");
    if (keyVersion < 1) {
      throw new IllegalArgumentException("Federation key version must be positive");
    }
    Objects.requireNonNull(createdOn, "createdOn");
  }

  private static void requireHttpUri(String value, String label) {
    try {
      URI uri = URI.create(value);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Federation " + label + " must be an absolute HTTP URI");
    }
  }
}
