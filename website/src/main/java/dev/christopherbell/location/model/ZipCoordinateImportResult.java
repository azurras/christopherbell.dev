package dev.christopherbell.location.model;

import lombok.Builder;

/**
 * Result summary for a ZIP coordinate dataset import.
 */
@Builder
public record ZipCoordinateImportResult(
    int processed,
    int created,
    int updated,
    int unchanged,
    int deleted,
    String source,
    int sourceYear
) {}
