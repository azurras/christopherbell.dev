package dev.christopherbell.post.discovery;

import dev.christopherbell.post.model.PostTopic;
import java.time.Instant;
import java.util.List;

/** Internal bounded candidate assembled from active public post activity. */
public record VoidPersonCandidate(
    String accountId, List<PostTopic> topics, Instant recentActivityOn) {
  public VoidPersonCandidate {
    topics = topics == null ? List.of() : List.copyOf(topics);
  }
}
