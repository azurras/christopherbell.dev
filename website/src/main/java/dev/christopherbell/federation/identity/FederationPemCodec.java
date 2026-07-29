package dev.christopherbell.federation.identity;

import java.util.Base64;
import java.util.Objects;

/** Encodes X.509 public keys using the standard PEM envelope. */
public final class FederationPemCodec {
  private static final Base64.Encoder MIME_ENCODER =
      Base64.getMimeEncoder(64, new byte[] {'\n'});

  private FederationPemCodec() {}

  public static String encodePublicKey(byte[] x509EncodedKey) {
    Objects.requireNonNull(x509EncodedKey, "x509EncodedKey");
    if (x509EncodedKey.length == 0) {
      throw new IllegalArgumentException("Federation public key must not be empty");
    }
    return "-----BEGIN PUBLIC KEY-----\n"
        + MIME_ENCODER.encodeToString(x509EncodedKey)
        + "\n-----END PUBLIC KEY-----";
  }
}
