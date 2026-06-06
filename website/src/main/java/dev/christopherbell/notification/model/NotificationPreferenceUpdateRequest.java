package dev.christopherbell.notification.model;

/** API request for replacing notification category settings. */
public record NotificationPreferenceUpdateRequest(
    Boolean mentions,
    Boolean likes,
    Boolean comments,
    Boolean messages,
    Boolean wflSessions
) {}
