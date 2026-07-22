package dev.christopherbell.sharedfolder.audit;

import java.time.Instant;

/** Optional ADMIN audit filters. Validation is performed before constructing a Mongo query. */
public record SharedFolderAuditFilter(
    String accountId,
    String action,
    String outcome,
    String relativePath,
    Instant from,
    Instant to,
    Integer limit) {}
