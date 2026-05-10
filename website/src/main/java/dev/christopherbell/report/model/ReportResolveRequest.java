package dev.christopherbell.report.model;

/**
 * Request payload for resolving a report.
 *
 * @param resolution action to apply
 */
public record ReportResolveRequest(
    ReportResolution resolution
) {}
