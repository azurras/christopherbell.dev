package dev.christopherbell.report.query;

import dev.christopherbell.report.model.PostReport;
import java.util.List;

/** Bounded report queue page with authoritative totals. */
public record ReportPage(
    List<PostReport> items,
    int page,
    int size,
    long totalElements,
    int totalPages) {

  public ReportPage {
    items = List.copyOf(items);
  }
}
