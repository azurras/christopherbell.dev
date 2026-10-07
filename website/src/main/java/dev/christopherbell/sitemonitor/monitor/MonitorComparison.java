package dev.christopherbell.sitemonitor.monitor;

import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Finding;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.FindingSeverity;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Page;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Report;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.ReportStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Evidence-based comparison; incomplete pages never imply successful verification. */
public final class MonitorComparison {

  private MonitorComparison() {
  }

  /**
   * Compares observed pages with the accepted baseline and classifies the check.
   *
   * <p>A repeated non-200 status or a failed asset is a failure. A check that could not complete
   * is incomplete. Metadata that differs from the baseline page is a change.</p>
   *
   * @param baselinePages the accepted baseline, empty before one exists
   * @param observedPages the pages captured by this check
   * @param checkedOn when this check ran
   * @param baselineOn when the compared baseline was accepted, or {@code null}
   * @return the report, with a new id
   */
  public static Report compare(List<Page> baselinePages, List<Page> observedPages,
      Instant checkedOn, Instant baselineOn) {
    List<Finding> findings = new ArrayList<>();
    for (Page observedPage : observedPages) {
      if (!observedPage.complete()) {
        findings.add(new Finding(
            FindingSeverity.INCOMPLETE, observedPage.path(), "coverage", "", observedPage.problem()));
      }
      if (observedPage.status() != 200) {
        FindingSeverity statusSeverity = observedPage.repeatedFailure()
            ? FindingSeverity.FAILURE
            : FindingSeverity.INCOMPLETE;
        findings.add(new Finding(statusSeverity, observedPage.path(), "HTTP status", "200",
            String.valueOf(observedPage.status())));
        continue;
      }
      for (String failedAsset : observedPage.failedAssets()) {
        findings.add(new Finding(FindingSeverity.FAILURE, observedPage.path(), "asset", "", failedAsset));
      }
      Optional<Page> baselinePage = baselinePages.stream()
          .filter(candidate -> candidate.path().equals(observedPage.path()))
          .findFirst();
      baselinePage.ifPresent(acceptedPage -> addMetadataChanges(findings, acceptedPage, observedPage));
    }
    return new Report(UUID.randomUUID().toString(), checkedOn, baselineOn,
        overallStatusOf(findings), findings, observedPages);
  }

  /**
   * Renders the plain-text client report, including what the check did not cover.
   *
   * @param siteLabel the owner's name for the site
   * @param siteOrigin the monitored HTTPS origin
   * @param report the report to render
   * @return the report text
   */
  public static String renderReport(String siteLabel, String siteOrigin, Report report) {
    StringBuilder reportText = new StringBuilder("Website Monitor — client report\n");
    reportText.append("Site: ").append(siteLabel)
        .append("\nOrigin: ").append(siteOrigin)
        .append("\nBaseline: ").append(report.baselineOn())
        .append("\nChecked: ").append(report.checkedOn())
        .append("\nResult: ").append(report.status())
        .append("\n\nCoverage: configured HTML pages and up to ten same-origin assets using HEAD.\n")
        .append("External/query assets, rendered layout, forms, logins and checkout are untested.\n")
        .append("HEALTHY means these bounded checks passed; it is not an uptime or security guarantee.\n\n");
    for (Page page : report.pages()) {
      reportText.append(page.path()).append(" — HTTP ").append(page.status())
          .append("; assets checked ").append(page.checkedAssets())
          .append(", omitted ").append(page.omittedAssets()).append("\n");
    }
    for (Finding finding : report.findings()) {
      reportText.append("\n").append(finding.severity()).append(" ").append(finding.path())
          .append(" / ").append(finding.field())
          .append("\nBefore: ").append(finding.before())
          .append("\nAfter: ").append(finding.after()).append("\n");
    }
    return reportText.toString();
  }

  private static void addMetadataChanges(List<Finding> findings, Page acceptedPage, Page observedPage) {
    String path = observedPage.path();
    addChangeIfDifferent(findings, path, "destination", acceptedPage.finalUrl(), observedPage.finalUrl());
    addChangeIfDifferent(findings, path, "title", acceptedPage.title(), observedPage.title());
    addChangeIfDifferent(
        findings, path, "description", acceptedPage.description(), observedPage.description());
    addChangeIfDifferent(findings, path, "canonical", acceptedPage.canonical(), observedPage.canonical());
    addChangeIfDifferent(findings, path, "robots", acceptedPage.robots(), observedPage.robots());
  }

  private static void addChangeIfDifferent(List<Finding> findings, String path, String field,
      String acceptedValue, String observedValue) {
    if (!acceptedValue.equals(observedValue)) {
      findings.add(new Finding(FindingSeverity.CHANGE, path, field, acceptedValue, observedValue));
    }
  }

  private static ReportStatus overallStatusOf(List<Finding> findings) {
    if (hasSeverity(findings, FindingSeverity.FAILURE)) {
      return ReportStatus.FAILURES;
    }
    if (hasSeverity(findings, FindingSeverity.INCOMPLETE)) {
      return ReportStatus.INCOMPLETE;
    }
    return findings.isEmpty() ? ReportStatus.HEALTHY : ReportStatus.CHANGES;
  }

  private static boolean hasSeverity(List<Finding> findings, FindingSeverity severity) {
    return findings.stream().anyMatch(finding -> finding.severity() == severity);
  }
}
