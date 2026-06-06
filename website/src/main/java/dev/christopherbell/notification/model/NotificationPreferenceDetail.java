package dev.christopherbell.notification.model;

import lombok.Builder;

/** API response describing the current notification category settings. */
@Builder
public record NotificationPreferenceDetail(
    boolean mentions,
    boolean likes,
    boolean comments,
    boolean messages,
    boolean wflSessions
) {}
