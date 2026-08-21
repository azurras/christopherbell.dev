package dev.christopherbell.libs.mongo.lease;

import java.time.Instant;

/** Transition-only facade for legacy Mongo lease callers. */
public class MongoLeaseService {
  private final MongoLeaseStore store;

  public MongoLeaseService(MongoLeaseStore store) { this.store = store; }

  public boolean tryAcquire(
      String name, String ownerToken, Instant now, Instant expiresAt) {
    return store.tryAcquire(name, ownerToken, now, expiresAt);
  }

  public boolean renew(String name, String ownerToken, Instant now, Instant expiresAt) {
    return store.renew(name, ownerToken, now, expiresAt);
  }

  public boolean release(String name, String ownerToken) {
    return store.release(name, ownerToken);
  }
}
