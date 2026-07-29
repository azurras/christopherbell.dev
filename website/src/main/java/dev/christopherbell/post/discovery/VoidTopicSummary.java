package dev.christopherbell.post.discovery;

import java.time.Instant;

/** Public topic metadata ordered by the topic's latest active activity. */
public record VoidTopicSummary(String canonical, String display, Instant activityOn) {}
