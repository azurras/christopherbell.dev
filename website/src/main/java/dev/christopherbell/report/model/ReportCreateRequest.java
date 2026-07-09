package dev.christopherbell.report.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for reporting a post.
 *
 * @param postId  id of the post being reported
 * @param reason  reason code for the report
 * @param details optional additional details
 */
public record ReportCreateRequest(
    @NotBlank
    @Size(max = 100)
    String postId,
    @NotBlank
    @Size(max = 80)
    String reason,
    @Size(max = 1000)
    String details
) {}
