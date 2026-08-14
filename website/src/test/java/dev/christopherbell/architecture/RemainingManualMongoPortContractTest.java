package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.configuration.mongo.migration.MigrationStateStore;
import dev.christopherbell.configuration.mongo.runtime.MongoApplicationLeaseStore;
import dev.christopherbell.configuration.mongo.runtime.MongoScheduledCollectorRunStore;
import dev.christopherbell.libs.mongo.lease.MongoLeaseStore;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStore;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditQueryService;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceLeaseStore;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Prevents a new untested operation from entering any Task 5 manual persistence boundary. */
class RemainingManualMongoPortContractTest {
  @Test
  void manualPortInventoriesAreExactAndAdaptersAreExplicit() throws Exception {
    assertThat(signatures(MongoLeaseStore.class)).isEqualTo(Set.of(
        "release(String,String)", "renew(String,String,Instant,Instant)",
        "tryAcquire(String,String,Instant,Instant)"));
    assertThat(signatures(ScheduledCollectorRunStore.class))
        .isEqualTo(Set.of("save(ScheduledCollectorRun)"));
    assertThat(signatures(SharedFolderMaintenanceLeaseStore.class)).isEqualTo(Set.of(
        "release(LeaseGrant)", "renew(LeaseGrant,Duration)",
        "tryAcquire(String,Duration)"));
    assertThat(publicDeclaredSignatures(MigrationStateStore.class)).isEqualTo(Set.of(
        "complete(String,String,Instant)", "fail(String,String,Instant,String)",
        "find(String)", "start(ApplicationMigration,String,Instant)"));
    assertThat(publicDeclaredSignatures(SharedFolderAuditQueryService.class))
        .isEqualTo(Set.of("search(SharedFolderAuditFilter)"));

    assertThat(MongoApplicationLeaseStore.class.getInterfaces()).contains(MongoLeaseStore.class);
    assertThat(MongoScheduledCollectorRunStore.class.getInterfaces())
        .contains(ScheduledCollectorRunStore.class);
    var maintenanceAdapter = Class.forName(
        "dev.christopherbell.sharedfolder.maintenance.MongoSharedFolderMaintenanceLeaseStore");
    assertThat(maintenanceAdapter.getInterfaces())
        .contains(SharedFolderMaintenanceLeaseStore.class);
  }

  private static Set<String> signatures(Class<?> type) {
    return Arrays.stream(type.getDeclaredMethods())
        .map(RemainingManualMongoPortContractTest::signature)
        .collect(Collectors.toSet());
  }

  private static Set<String> publicDeclaredSignatures(Class<?> type) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .map(RemainingManualMongoPortContractTest::signature)
        .collect(Collectors.toSet());
  }

  private static String signature(Method method) {
    return method.getName() + Arrays.stream(method.getParameterTypes())
        .map(Class::getSimpleName)
        .collect(Collectors.joining(",", "(", ")"));
  }
}
