package dev.christopherbell.post.discovery;

import java.time.Instant;
import java.util.List;

/** Presentation-safe account suggestion; no email or engagement totals are exposed. */
public record VoidPersonSuggestion(
    String accountId,
    String username,
    List<String> sharedTopics,
    Instant recentActivityOn,
    boolean followed
) {
  public VoidPersonSuggestion {
    sharedTopics = List.copyOf(sharedTopics);
  }
}
