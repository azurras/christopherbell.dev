package dev.christopherbell.report.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for resolving a report.
 *
 * @param resolution action to apply
 * @param reason moderator-supplied audit reason
 */
public record ReportResolveRequest(
    ReportResolution resolution,
    @NotBlank @Size(max = 500) String reason
) {}
