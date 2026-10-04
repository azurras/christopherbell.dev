package dev.christopherbell.sitemonitor.monitor;

import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Finding;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Page;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Report;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Evidence-based comparison; incomplete pages never imply successful verification. */
public final class MonitorComparison {
  private MonitorComparison() {}

  public static Report compare(List<Page> baseline, List<Page> observed, Instant checkedOn,
      Instant baselineOn) {
    List<Finding> findings = new ArrayList<>();
    for (Page page : observed) {
      if (!page.complete()) {
        findings.add(new Finding("INCOMPLETE", page.path(), "coverage", "", page.problem()));
      }
      if (page.status() != 200) {
        findings.add(new Finding(page.repeatedFailure() ? "FAILURE" : "INCOMPLETE",
            page.path(), "HTTP status", "200", String.valueOf(page.status())));
        continue;
      }
      for (String asset : page.failedAssets()) {
        findings.add(new Finding("FAILURE", page.path(), "asset", "", asset));
      }
      var previous = baseline.stream().filter(value -> value.path().equals(page.path())).findFirst();
      if (previous.isEmpty()) continue;
      Page accepted = previous.get();
      changed(findings, page.path(), "destination", accepted.finalUrl(), page.finalUrl());
      changed(findings, page.path(), "title", accepted.title(), page.title());
      changed(findings, page.path(), "description", accepted.description(), page.description());
      changed(findings, page.path(), "canonical", accepted.canonical(), page.canonical());
      changed(findings, page.path(), "robots", accepted.robots(), page.robots());
    }
    String status = findings.stream().anyMatch(f -> f.severity().equals("FAILURE")) ? "FAILURES"
        : findings.stream().anyMatch(f -> f.severity().equals("INCOMPLETE")) ? "INCOMPLETE"
        : findings.isEmpty() ? "HEALTHY" : "CHANGES";
    return new Report(UUID.randomUUID().toString(), checkedOn, baselineOn, status, findings, observed);
  }

  private static void changed(List<Finding> findings, String path, String field,
      String before, String after) {
    if (!before.equals(after)) findings.add(new Finding("CHANGE", path, field, before, after));
  }

  public static String renderReport(String label, String origin, Report report) {
    StringBuilder text = new StringBuilder("Website Monitor — client report\n");
    text.append("Site: ").append(label).append("\nOrigin: ").append(origin)
        .append("\nBaseline: ").append(report.baselineOn()).append("\nChecked: ").append(report.checkedOn())
        .append("\nResult: ").append(report.status())
        .append("\n\nCoverage: configured HTML pages and up to ten same-origin assets using HEAD.\n")
        .append("External/query assets, rendered layout, forms, logins and checkout are untested.\n")
        .append("HEALTHY means these bounded checks passed; it is not an uptime or security guarantee.\n\n");
    for (Page page : report.pages()) {
      text.append(page.path()).append(" — HTTP ").append(page.status())
          .append("; assets checked ").append(page.checkedAssets())
          .append(", omitted ").append(page.omittedAssets()).append("\n");
    }
    for (Finding finding : report.findings()) {
      text.append("\n").append(finding.severity()).append(" ").append(finding.path())
          .append(" / ").append(finding.field()).append("\nBefore: ").append(finding.before())
          .append("\nAfter: ").append(finding.after()).append("\n");
    }
    return text.toString();
  }
}
