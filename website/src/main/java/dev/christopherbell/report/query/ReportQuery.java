package dev.christopherbell.report.query;

import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import java.time.Instant;

/** Typed report queue filters and bounded page coordinates. */
public record ReportQuery(
    ReportStatus status,
    ReportType reportType,
    ReportTargetType targetType,
    String reporter,
    Instant from,
    Instant to,
    int page,
    int size) {}
