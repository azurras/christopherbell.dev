package dev.christopherbell.post.feed;

import java.util.Collection;
import java.util.Map;

/** Persistence-neutral page-wide post engagement query boundary. */
public interface PostEngagementQueryPort {
  Map<String, Integer> replyCounts(Collection<String> postIds);
}
