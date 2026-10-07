package dev.christopherbell.sitemonitor.monitor;

import static org.assertj.core.api.Assertions.*;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.FindingSeverity;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.ReportStatus;
import dev.christopherbell.sitemonitor.fetch.*;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MonitorScannerTest {
  private Site site(boolean demo) {
    return new Site("s", "Client", "https://example.com", List.of("/"), "proof", demo,
        null, null, null, List.of(), List.of());
  }
  @Test
  void verificationRequiresExactUnredirectedTokenAndDemoCannotChooseOtherSites() {
    var scanner = new MonitorScanner((uri, origin, deadline, head) ->
        new MonitorGateway.Result(200, uri, "text/plain", "proof\n".getBytes(StandardCharsets.UTF_8)));
    assertThat(scanner.isOwnershipVerified(site(false), MonitorScanner.newRunDeadlineNanos())).isTrue();
    assertThat(scanner.isOwnershipVerified(site(true), MonitorScanner.newRunDeadlineNanos())).isFalse();
    var redirected = new MonitorScanner((uri, origin, deadline, head) ->
        new MonitorGateway.Result(200, origin, "text/plain", "proof".getBytes(StandardCharsets.UTF_8)));
    assertThat(redirected.isOwnershipVerified(site(false), MonitorScanner.newRunDeadlineNanos())).isFalse();
  }
  @Test
  void capturesMetadataAndBoundsAssetsWithoutFetchingExternalUrls() {
    var assets = new AtomicInteger();
    var scanner = new MonitorScanner((uri, origin, deadline, head) -> {
      if (head) { assets.incrementAndGet(); return new MonitorGateway.Result(200, uri, "", new byte[0]); }
      String html = "<title>Client</title><meta name=robots content=noindex><script src=/app.js></script>"
          + "<img src=https://other.example/pixel><img src='/query.png?v=1'>";
      return new MonitorGateway.Result(200, uri, "text/html", html.getBytes(StandardCharsets.UTF_8), "nofollow");
    });
    var pages = scanner.capturePages(site(false), MonitorScanner.newRunDeadlineNanos());
    assertThat(pages.getFirst().title()).isEqualTo("Client");
    assertThat(pages.getFirst().robots()).contains("noindex", "nofollow");
    assertThat(pages.getFirst().omittedAssets()).isEqualTo(2);
    assertThat(assets).hasValue(1);
  }
  @Test
  void repeatedHttpFailureIsFailureWhileTimeoutIsIncomplete() {
    var failing = new MonitorScanner((uri, origin, deadline, head) ->
        new MonitorGateway.Result(404, uri, "", new byte[0]));
    var report = MonitorComparison.compare(List.of(), failing.capturePages(site(false), MonitorScanner.newRunDeadlineNanos()), Instant.EPOCH, Instant.EPOCH);
    assertThat(report.status()).isEqualTo(ReportStatus.FAILURES);
    var timeout = new MonitorScanner((uri, origin, deadline, head) -> { throw new MonitorFetchException("TIMEOUT"); });
    var incomplete = MonitorComparison.compare(List.of(), timeout.capturePages(site(false), MonitorScanner.newRunDeadlineNanos()), Instant.EPOCH, Instant.EPOCH);
    assertThat(incomplete.status()).isEqualTo(ReportStatus.INCOMPLETE);
  }
  @Test
  void changingHttpStatusCannotProduceAnEmptyHealthyBaseline() {
    var calls = new AtomicInteger();
    var scanner = new MonitorScanner((uri, origin, deadline, head) ->
        new MonitorGateway.Result(calls.incrementAndGet() == 1 ? 500 : 200, uri, "text/html", new byte[0]));
    var page = scanner.capturePages(site(false), MonitorScanner.newRunDeadlineNanos()).getFirst();
    assertThat(page.healthy()).isFalse();
    assertThat(page.problem()).isEqualTo("HTTP_STATUS_CHANGED_DURING_CHECK");
  }
  @Test
  void changesAreObservationsAndReportStatesUntestedCoverage() {
    Page original = new Page("/", 200, "https://example.com/", "Old", "", "", "", List.of(), 0, 0, "", false);
    Page changed = new Page("/", 200, "https://example.com/", "New", "", "", "noindex", List.of(), 0, 0, "", false);
    var report = MonitorComparison.compare(List.of(original), List.of(changed), Instant.EPOCH, Instant.EPOCH);
    assertThat(report.status()).isEqualTo(ReportStatus.CHANGES);
    assertThat(report.findings()).hasSize(2).allMatch(finding -> finding.severity() == FindingSeverity.CHANGE);
    assertThat(MonitorComparison.renderReport("Client", "https://example.com", report))
        .contains("Old", "New", "untested", "not an uptime or security guarantee");
  }
}
