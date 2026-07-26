package dev.christopherbell.post.editing;

import java.time.Instant;

/** Bounded before/after evidence for one post text edit. */
public record PostEditAuditEvent(
    String editorAccountId,
    String beforeText,
    String afterText,
    Instant editedOn) {}
