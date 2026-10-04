package dev.christopherbell.sitemonitor.monitor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import dev.christopherbell.account.api.MonitorAccountAccess;
import dev.christopherbell.libs.lease.LeaseGrant;
import dev.christopherbell.libs.lease.LeaseStore;
import dev.christopherbell.sitemonitor.api.MonitorProblem;
import dev.christopherbell.sitemonitor.fetch.*;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace;
import dev.christopherbell.sitemonitor.persistence.MonitorWorkspaceRepository;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SiteMonitorServiceTest {
  final MonitorAccountAccess accounts = mock(MonitorAccountAccess.class);
  final LeaseStore leases = mock(LeaseStore.class);
  final Store store = new Store();
  final MutableClock clock = new MutableClock();
  String title = "Accepted";
  boolean failed = false;
  boolean ownership = true;
  SiteMonitorService service;
  @BeforeEach void setup() {
    when(accounts.requireCurrentActiveAccount()).thenReturn("alice");
    when(accounts.isActive(anyString())).thenReturn(true);
    when(leases.tryAcquire(anyString(), anyString(), any())).thenAnswer(call -> Optional.of(
        new LeaseGrant(call.getArgument(0), call.getArgument(1), 1, clock.instant().plusSeconds(180))));
    when(leases.renew(any(), any())).thenAnswer(call -> Optional.ofNullable(call.getArgument(0)));
    when(leases.release(any())).thenReturn(true);
    var policy = new SiteMonitorDestinationPolicy(host -> List.of(InetAddress.getByName("8.8.8.8")));
    var scanner = new MonitorScanner((uri, origin, deadline, head) -> {
      if (uri.getPath().contains("well-known")) return new MonitorGateway.Result(200, uri, "text/plain",
          (ownership ? store.find("alice").get().sites().getFirst().token() : "wrong").getBytes(StandardCharsets.UTF_8));
      return new MonitorGateway.Result(failed ? 500 : 200, uri, "text/html",
          ("<title>" + title + "</title>").getBytes(StandardCharsets.UTF_8));
    });
    service = new SiteMonitorService(store, accounts, leases, policy, scanner, clock);
  }
  @Test void ownershipBaselineCooldownComparisonAndRetention() {
    var workspace = service.addSite("Client", "https://example.com", List.of("/"), false);
    String id = workspace.sites().getFirst().id();
    var accepted = service.run(id, true).sites().getFirst();
    assertThat(accepted.baseline()).hasSize(1);
    assertThat(accepted.reports().getFirst().status()).isEqualTo("BASELINE");
    assertThatThrownBy(() -> service.run(id, false)).isInstanceOf(MonitorProblem.class)
        .extracting("status").isEqualTo(429);
    title = "Changed";
    for (int index = 0; index < 12; index++) { clock.advance(16); service.run(id, false); }
    var observed = service.currentWorkspace().sites().getFirst();
    assertThat(observed.reports()).hasSize(10);
    assertThat(observed.reports().getFirst().status()).isEqualTo("CHANGES");
    assertThat(observed.baseline().getFirst().title()).isEqualTo("Accepted");
    assertThat(service.report(id, observed.reports().getFirst().id())).contains("Changed", "untested");
  }
  @Test void incompleteCaptureAndOwnershipLossPreserveApprovedBaseline() {
    String id = service.addSite("Client", "https://example.com", List.of("/"), false).sites().getFirst().id();
    service.run(id, true); clock.advance(16); failed = true;
    var attempted = service.run(id, true).sites().getFirst();
    assertThat(attempted.baseline().getFirst().title()).isEqualTo("Accepted");
    assertThat(attempted.reports().getFirst().status()).isEqualTo("INCOMPLETE");
    clock.advance(16); ownership = false;
    var unverified = service.run(id, false).sites().getFirst();
    assertThat(unverified.verifiedOn()).isNull();
    assertThat(unverified.reports().getFirst().pages()).isEmpty();
    assertThat(unverified.baseline()).isEqualTo(attempted.baseline());
  }
  @Test void otherAccountsCannotReadMutateOrExportASite() {
    String id = service.addSite("Client", "https://example.com", List.of("/"), false).sites().getFirst().id();
    when(accounts.requireCurrentActiveAccount()).thenReturn("bob");
    assertThat(service.currentWorkspace().sites()).isEmpty();
    assertThatThrownBy(() -> service.removeSite(id)).isInstanceOf(MonitorProblem.class)
        .extracting("status").isEqualTo(404);
    assertThatThrownBy(() -> service.report(id, "anything")).isInstanceOf(MonitorProblem.class);
    assertThatThrownBy(() -> service.run(id, true)).isInstanceOf(MonitorProblem.class);
  }
  @Test void capacityLeaseLossAndRemovalAreBounded() {
    for (int i = 0; i < 5; i++) service.addSite("Client", "https://site" + i + ".example", List.of("/"), false);
    assertThatThrownBy(() -> service.addSite("Sixth", "https://site6.example", List.of("/"), false))
        .isInstanceOf(MonitorProblem.class);
    String id = store.find("alice").get().sites().getFirst().id();
    when(leases.renew(any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.removeSite(id)).isInstanceOf(MonitorProblem.class);
    assertThat(store.find("alice").get().sites()).hasSize(5);
    verify(leases, atLeastOnce()).release(any());
  }
  @Test void dailyChecksWaitUntilDueAndPurgeInactiveOwners() {
    String id = service.addSite("Client", "https://example.com", List.of("/"), false).sites().getFirst().id();
    service.run(id, true);
    service.checkNextDueSite(); assertThat(service.currentWorkspace().sites().getFirst().reports()).hasSize(1);
    clock.advance(24 * 60); service.checkNextDueSite();
    assertThat(service.currentWorkspace().sites().getFirst().reports()).hasSize(2);
    when(accounts.isActive("alice")).thenReturn(false); service.checkNextDueSite();
    assertThat(store.find("alice")).isEmpty();
  }
  @Test void historicalExportKeepsTheBaselineUsedByThatCheck() {
    String id = service.addSite("Client", "https://example.com", List.of("/"), false).sites().getFirst().id();
    var baseline = service.run(id, true).sites().getFirst();
    clock.advance(16); title = "Changed";
    var report = service.run(id, false).sites().getFirst().reports().getFirst();
    clock.advance(16); service.run(id, true);
    assertThat(service.report(id, report.id())).contains("Baseline: " + baseline.baselineOn());
    assertThat(report.baselineOn()).isEqualTo(baseline.baselineOn());
  }
  @Test void leaseLossAfterOwnershipStopsBeforePageFetch() {
    String id = service.addSite("Client", "https://example.com", List.of("/"), false).sites().getFirst().id();
    var pageRequests = new java.util.concurrent.atomic.AtomicInteger();
    var scanner = new MonitorScanner((uri, origin, deadline, head) -> {
      if (uri.getPath().contains("well-known")) {
        doReturn(Optional.empty()).when(leases).renew(any(), any());
        return new MonitorGateway.Result(200, uri, "text/plain", store.find("alice").get()
            .sites().getFirst().token().getBytes(StandardCharsets.UTF_8));
      }
      pageRequests.incrementAndGet();
      throw new AssertionError("page fetched after lease loss");
    });
    service = new SiteMonitorService(store, accounts, leases,
        new SiteMonitorDestinationPolicy(host -> List.of(InetAddress.getByName("8.8.8.8"))), scanner, clock);
    assertThatThrownBy(() -> service.run(id, true)).isInstanceOf(MonitorProblem.class);
    assertThat(pageRequests).hasValue(0);
    assertThat(store.find("alice").get().sites().getFirst().baseline()).isEmpty();
  }
  @Test void schedulerMinuteIsSharedAcrossSequentialRuns() {
    var first = service.addSite("First", "https://example.com", List.of("/"), false).sites().getFirst();
    service.run(first.id(), true);
    var second = service.addSite("Second", "https://second.example", List.of("/"), false).sites().getLast();
    // Both token proofs use this fake gateway's first token; only the demo bypass is appropriate here.
    var secondDemo = new MonitorWorkspace.Site(second.id(), second.label(), MonitorUrls.DEMO_ORIGIN,
        MonitorUrls.DEMO_PATHS, second.token(), true, null, null, null, List.of(), List.of());
    store.save(new MonitorWorkspace("alice", 1L, "alice", List.of(service.currentWorkspace().sites().getFirst(), secondDemo)));
    service.run(second.id(), true);
    clock.advance(24 * 60);
    service.checkNextDueSite(); service.checkNextDueSite();
    assertThat(store.find("alice").get().sites()).extracting(site -> site.reports().size())
        .containsExactly(2, 1);
    clock.advance(1); service.checkNextDueSite();
    assertThat(store.find("alice").get().sites()).extracting(site -> site.reports().size())
        .containsExactly(2, 2);
  }
  static class Store implements MonitorWorkspaceRepository {
    final Map<String, MonitorWorkspace> values = new HashMap<>();
    Instant nextScheduled;
    public boolean claimScheduledMinute(Instant now) {
      if (nextScheduled != null && nextScheduled.isAfter(now)) return false;
      nextScheduled = now.plusSeconds(60); return true;
    }
    public Optional<MonitorWorkspace> find(String id) { return Optional.ofNullable(values.get(id)); }
    public MonitorWorkspace save(MonitorWorkspace workspace) {
      var saved = new MonitorWorkspace(workspace.id() == null ? workspace.accountId() : workspace.id(),
          workspace.version() == null ? 0 : workspace.version() + 1, workspace.accountId(),
          workspace.generation() == null ? UUID.randomUUID().toString() : workspace.generation(), workspace.sites());
      values.put(saved.accountId(), saved); return saved;
    }
    public List<MonitorWorkspace> list() { return List.copyOf(values.values()); }
    public long count() { return values.size(); }
    public void delete(MonitorWorkspace expected) { values.remove(expected.accountId()); }
  }
  static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-03T00:00:00Z");
    void advance(int minutes) { now = now.plusSeconds(minutes * 60L); }
    public Instant instant() { return now; }
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
  }
}
