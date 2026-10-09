package dev.christopherbell.configuration.mongo.domain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClients;
import dev.christopherbell.account.api.MonitorAccountAccess;
import dev.christopherbell.configuration.mongo.runtime.MongoApplicationLeaseStore;
import dev.christopherbell.sitemonitor.api.MonitorProblem;
import dev.christopherbell.sitemonitor.fetch.MonitorGateway;
import dev.christopherbell.sitemonitor.fetch.SiteMonitorDestinationPolicy;
import dev.christopherbell.sitemonitor.model.CreateMonitorSite;
import dev.christopherbell.sitemonitor.model.MonitorSchedule;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace;
import dev.christopherbell.sitemonitor.monitor.MonitorScanner;
import dev.christopherbell.sitemonitor.monitor.SiteMonitorService;
import dev.christopherbell.sitemonitor.persistence.MongoMonitorWorkspaceRepository;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Opt-in proof against an explicitly isolated local test database; never production. */
@EnabledIfEnvironmentVariable(named = "SITE_MONITOR_TEST_MONGO_URI", matches = ".+")
class SiteMonitorMongoIntegrationTest {
  @Test void realMongoAndLeaseRetainNestedReportsAndRunOnlyDueChecks() throws Exception {
    String uri = System.getenv("SITE_MONITOR_TEST_MONGO_URI");
    var connection = new ConnectionString(uri);
    assertThat(connection.getDatabase()).isEqualTo("test");
    assertThat(connection.getHosts()).hasSize(1);
    assertThat(connection.getHosts().getFirst()).startsWith("127.0.0.1:").doesNotEndWith(":27017");
    try (var client = MongoClients.create(uri)) {
      var factory = new DomainMongoOperationsFactory(new MongoTemplate(client, "test"));
      var repository = new MongoMonitorWorkspaceRepository(factory);
      assertThat(repository.listAll()).isEmpty();
      var accounts = mock(MonitorAccountAccess.class);
      when(accounts.requireCurrentActiveAccount()).thenReturn("monitor-test-lifecycle");
      when(accounts.isActive("monitor-test-lifecycle")).thenReturn(true);
      var now = new AtomicReference<>(Instant.parse("2026-10-03T00:00:00Z"));
      var clock = new Clock() {
        public Instant instant() { return now.get(); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
      };
      var status = new AtomicInteger(200);
      var title = new AtomicReference<>("Accepted");
      var scanner = new MonitorScanner((target, origin, deadline, head) -> {
        String body = target.getPath().contains("well-known")
            ? repository.findByAccountId("monitor-test-lifecycle").orElseThrow().sites().getFirst().token()
            : "<title>" + title.get() + "</title>";
        return new MonitorGateway.Result(
            target.getPath().contains("well-known") ? 200 : status.get(), target,
            target.getPath().contains("well-known") ? "text/plain" : "text/html", body.getBytes(StandardCharsets.UTF_8));
      });
      var service = new SiteMonitorService(repository, accounts,
          new MongoApplicationLeaseStore(factory),
          new SiteMonitorDestinationPolicy(
              host -> List.of(InetAddress.getByName("8.8.8.8"))), scanner, clock);
      try {
        String id = service.addSite(new CreateMonitorSite("Client", "https://client.example", List.of("/"), false)).sites().getFirst().id();
        var baseline = service.captureBaseline(id).sites().getFirst();
        title.set("Changed"); now.set(now.get().plusSeconds(24 * 60 * 60));
        service.checkNextDueSite();
        var changed = new MongoMonitorWorkspaceRepository(factory).findByAccountId("monitor-test-lifecycle").orElseThrow().sites().getFirst();
        assertThat(changed.reports().getFirst().status()).isEqualTo(MonitorWorkspace.ReportStatus.CHANGES);
        assertThat(changed.reports().getFirst().baselineOn()).isEqualTo(baseline.baselineOn());
        assertThat(service.renderReport(id, changed.reports().getFirst().id())).contains("Accepted", "Changed");
        service.checkNextDueSite();
        assertThat(service.currentWorkspace().sites().getFirst().reports()).hasSize(2);
        now.set(now.get().plusSeconds(16 * 60)); status.set(500);
        var failedCapture = service.captureBaseline(id).sites().getFirst();
        assertThat(failedCapture.reports().getFirst().status()).isEqualTo(MonitorWorkspace.ReportStatus.INCOMPLETE);
        assertThat(failedCapture.baseline()).isEqualTo(baseline.baseline());
        service.removeSite(id);
        assertThat(repository.findByAccountId("monitor-test-lifecycle")).isEmpty();
      } finally {
        repository.findByAccountId("monitor-test-lifecycle").ifPresent(repository::delete);
        factory.forType(MonitorSchedule.class)
            .remove(Query.query(
                Criteria.where("id").is("daily")));
      }
    }
  }
  @Test void fixedSlotsVersionsThrottleAndAccountCleanupRemainIsolated() {
    String uri = System.getenv("SITE_MONITOR_TEST_MONGO_URI");
    var connection = new ConnectionString(uri);
    assertThat(connection.getDatabase()).isEqualTo("test");
    assertThat(connection.getHosts()).hasSize(1);
    assertThat(connection.getHosts().getFirst()).startsWith("127.0.0.1:").doesNotEndWith(":27017");
    try (var client = MongoClients.create(uri)) {
      var mongo = new MongoTemplate(client, "test");
      var factory = new DomainMongoOperationsFactory(mongo);
      var repository = new MongoMonitorWorkspaceRepository(factory);
      assertThat(repository.listAll()).isEmpty();
      var indexesBefore = mongo.getCollection("application_runtime").listIndexes()
          .into(new ArrayList<>()).stream().map(document -> document.getString("name")).toList();
      try {
        var first = repository.save(new MonitorWorkspace(null, null, "monitor-test-0", List.of()));
        assertThat(first.id()).isEqualTo("pilot-0");
        assertThat(first.version()).isZero();
        var updated = repository.save(first);
        assertThat(updated.version()).isEqualTo(1);
        assertThatThrownBy(() -> repository.save(first)).isInstanceOf(OptimisticLockingFailureException.class);
        for (int i = 1; i < 10; i++) {
          repository.save(new MonitorWorkspace(null, null, "monitor-test-" + i, List.of()));
        }
        assertThat(repository.count()).isEqualTo(10);
        assertThatThrownBy(() -> repository.save(new MonitorWorkspace(null, null, "monitor-test-extra", List.of())))
            .isInstanceOf(MonitorProblem.class);
        var now = Instant.parse("2026-10-03T00:00:00Z");
        assertThat(repository.claimScheduledMinute(now)).isTrue();
        assertThat(new MongoMonitorWorkspaceRepository(factory).claimScheduledMinute(now.plusSeconds(30))).isFalse();
        assertThat(repository.claimScheduledMinute(now.plusSeconds(60))).isTrue();
        new DomainAccountDeletionStore(factory).removePrivateData("monitor-test-0");
        assertThat(repository.findByAccountId("monitor-test-0")).isEmpty();
        assertThat(repository.findByAccountId("monitor-test-1")).isPresent();
        assertThatThrownBy(() -> repository.save(updated)).isInstanceOf(OptimisticLockingFailureException.class);
        var reused = repository.save(new MonitorWorkspace(null, null, "monitor-test-bob", List.of()));
        assertThat(reused.id()).isEqualTo(first.id());
        assertThat(reused.version()).isZero();
        assertThatThrownBy(() -> repository.save(first)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> repository.delete(first)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(repository.findByAccountId("monitor-test-bob")).contains(reused);
        repository.delete(reused);
        var sameOwnerNewGeneration = repository.save(new MonitorWorkspace(null, null, "monitor-test-bob", List.of()));
        assertThatThrownBy(() -> repository.save(reused)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> repository.delete(reused)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(repository.findByAccountId("monitor-test-bob")).contains(sameOwnerNewGeneration);
        assertThat(mongo.getCollection("application_runtime").listIndexes().into(new ArrayList<>()))
            .extracting(document -> document.getString("name")).containsExactlyElementsOf(indexesBefore);
      } finally {
        for (int i = 0; i < 10; i++) repository.findByAccountId("monitor-test-" + i).ifPresent(repository::delete);
        repository.findByAccountId("monitor-test-bob").ifPresent(repository::delete);
        factory.forType(MonitorSchedule.class)
            .remove(Query.query(
                Criteria.where("id").is("daily")));
      }
    }
  }
}
