package dev.christopherbell.post.discovery;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Persistence-neutral people discovery query boundary. */
public interface VoidPeopleDiscoveryQueryPort {
  Set<String> interestsFor(String accountId, Instant now);

  List<VoidPersonCandidate> recentActiveCandidates(Instant now, int requestedLimit);
}
