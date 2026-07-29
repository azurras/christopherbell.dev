package dev.christopherbell.post.feed;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** Query-time visibility constraints applied before feed capacity and cursor calculation. */
public record PostFeedVisibility(
    Set<String> excludedAccountIds,
    Set<String> excludedRootIds,
    Optional<Instant> expiresAfter
) {
  public PostFeedVisibility {
    excludedAccountIds = excludedAccountIds == null ? Set.of() : Set.copyOf(excludedAccountIds);
    excludedRootIds = excludedRootIds == null ? Set.of() : Set.copyOf(excludedRootIds);
    expiresAfter = expiresAfter == null ? Optional.empty() : expiresAfter;
  }

  public static PostFeedVisibility unrestricted() {
    return new PostFeedVisibility(Set.of(), Set.of(), Optional.empty());
  }
}
