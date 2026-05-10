package dev.christopherbell.report.model;

/**
 * Request payload for reporting a post.
 *
 * @param postId  id of the post being reported
 * @param reason  reason code for the report
 * @param details optional additional details
 */
public record ReportCreateRequest(
    String postId,
    String reason,
    String details
) {}
