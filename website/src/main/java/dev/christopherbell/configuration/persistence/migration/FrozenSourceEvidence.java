package dev.christopherbell.configuration.persistence.migration;

import java.util.UUID;

/** Evidence binding a stopped-writer source to one exact finalization request. */
public record FrozenSourceEvidence(
    String release,
    String catalogDigest,
    String sourceDatabase,
    String targetDatabase,
    String sourceDigest,
    String backupDigest,
    UUID lockToken) {}
