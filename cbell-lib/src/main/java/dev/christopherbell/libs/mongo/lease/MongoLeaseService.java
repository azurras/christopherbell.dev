package dev.christopherbell.libs.mongo.lease;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Provides atomic, owner-scoped leases stored in MongoDB. */
@Service
@RequiredArgsConstructor
public class MongoLeaseService {
  private final MongoLeaseStore store;

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
