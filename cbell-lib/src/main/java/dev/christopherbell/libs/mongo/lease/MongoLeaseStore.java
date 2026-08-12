package dev.christopherbell.libs.mongo.lease;

import java.time.Instant;

/** Atomic owner-scoped lease persistence supplied by the consuming application. */
public interface MongoLeaseStore {
  boolean tryAcquire(String name, String ownerToken, Instant now, Instant expiresAt);

  boolean renew(String name, String ownerToken, Instant now, Instant expiresAt);

  boolean release(String name, String ownerToken);
}
