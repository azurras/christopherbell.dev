package dev.christopherbell.federation.discovery;

import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.Optional;

/** Persistence-neutral local actor outbox query boundary. */
public interface FederationOutboxQueryPort {
  FederationPage<Post> page(
      String accountId, Optional<StableCursor> cursor, int requestedSize, Instant now);

  long count(String accountId, Instant now);
}
