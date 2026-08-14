package dev.christopherbell.federation.api;

import java.util.Objects;

/** AES-GCM sealed private-key bytes and the unique nonce used to seal them. */
public record EncryptedPrivateKey(byte[] nonce, byte[] ciphertext) {
  private static final int NONCE_BYTES = 12;
  private static final int AUTHENTICATION_TAG_BYTES = 16;

  public EncryptedPrivateKey {
    Objects.requireNonNull(nonce, "nonce");
    Objects.requireNonNull(ciphertext, "ciphertext");
    if (nonce.length != NONCE_BYTES) {
      throw new IllegalArgumentException("Federation private-key nonce must be 12 bytes");
    }
    if (ciphertext.length < AUTHENTICATION_TAG_BYTES) {
      throw new IllegalArgumentException("Federation private-key ciphertext is invalid");
    }
    nonce = nonce.clone();
    ciphertext = ciphertext.clone();
  }

  @Override
  public byte[] nonce() {
    return nonce.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }
}
