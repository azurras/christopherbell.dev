package dev.christopherbell.sitemonitor.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

/** One account's bounded pilot state; versioned saves protect concurrent lifecycle writes. */
public record MonitorWorkspace(@Id String id, @Version Long version, String accountId,
    String generation, List<Site> sites) {
  public MonitorWorkspace { sites = List.copyOf(sites); }
  public MonitorWorkspace(String id, Long version, String accountId, List<Site> sites) {
    this(id, version, accountId, null, sites);
  }

  /** Fixed-origin monitored site. Tokens and all observations remain private to its owner. */
  public record Site(String id, String label, String origin, List<String> paths, String token,
      boolean demo, Instant verifiedOn, Instant lastAttempt, Instant baselineOn,
      List<Page> baseline, List<Report> reports) {
    public Site {
      paths = List.copyOf(paths);
      baseline = List.copyOf(baseline);
      reports = List.copyOf(reports);
    }
  }

  /** Bounded HTML metadata and same-origin asset results; raw remote HTML is never retained. */
  public record Page(String path, int status, String finalUrl, String title, String description,
      String canonical, String robots, List<String> failedAssets, int checkedAssets,
      int omittedAssets, String problem, boolean repeatedFailure) {
    public Page { failedAssets = List.copyOf(failedAssets); }
    public boolean complete() { return problem.isEmpty(); }
    public boolean healthy() { return complete() && status == 200 && failedAssets.isEmpty(); }
  }

  /** A client's evidence record, limited to the ten latest checks per site. */
  public record Report(String id, Instant checkedOn, Instant baselineOn, String status,
      List<Finding> findings, List<Page> pages) {
    public Report { findings = List.copyOf(findings); pages = List.copyOf(pages); }
  }

  /** Observed evidence is labeled separately from repeated failures and incomplete checks. */
  public record Finding(String severity, String path, String field, String before, String after) {}
}
