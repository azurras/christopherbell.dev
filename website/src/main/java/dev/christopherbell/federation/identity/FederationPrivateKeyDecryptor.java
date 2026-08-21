package dev.christopherbell.federation.identity;

import dev.christopherbell.federation.api.FederationIdentity;

/** Decrypts one account-bound federation key for immediate in-memory use. */
@FunctionalInterface
public interface FederationPrivateKeyDecryptor {
  byte[] decrypt(String accountId, FederationIdentity identity);
}
