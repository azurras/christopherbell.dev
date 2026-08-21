package dev.christopherbell.configuration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Discovery-derived Task 5 port, adapter, and real-engine contract inventory. */
class PostgresTask5AdapterContractTest {
  @Test
  void discoveredMongoPortsHaveOnePostgresqlAdapterAndRealContractCoverage() {
    var classes = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.christopherbell");
    var requiredPorts = classes.stream()
        .filter(type -> type.isAnnotatedWith(MongoPersistence.class))
        .flatMap(type -> type.getAllRawInterfaces().stream())
        .map(JavaClass::getName)
        .filter(PostgresTask5AdapterContractTest::isTask5Port)
        .collect(Collectors.toCollection(TreeSet::new));
    var adaptersByPort = new TreeMap<String, Set<String>>();
    classes.stream()
        .filter(type -> type.isAnnotatedWith(PostgresPersistence.class))
        .forEach(adapter -> adapter.getAllRawInterfaces().stream()
            .map(JavaClass::getName)
            .filter(requiredPorts::contains)
            .forEach(port -> adaptersByPort.computeIfAbsent(port, ignored -> new TreeSet<>())
                .add(adapter.getName())));
    var contractGroupsByPort = contractGroupsByPort();

    assertThat(requiredPorts).hasSize(24);
    assertThat(adaptersByPort.keySet()).containsExactlyElementsOf(requiredPorts);
    assertThat(adaptersByPort).allSatisfy((port, adapters) ->
        assertThat(adapters).as(port).singleElement());
    assertThat(contractGroupsByPort.keySet()).containsExactlyElementsOf(requiredPorts);
    assertThat(contractGroupsByPort).allSatisfy((port, groups) ->
        assertThat(groups).as(port).isNotEmpty());
    assertThat(Task5PersistenceParityContract.class)
        .isAssignableFrom(PostgresTask5ParityContractTest.class)
        .isAssignableFrom(MongoTask5ParityContractTest.class);
  }

  private static Map<String, Set<String>> contractGroupsByPort() {
    var result = new TreeMap<String, Set<String>>();
    Arrays.stream(Task5PersistenceParityContract.class.getDeclaredMethods())
        .filter(method -> method.isAnnotationPresent(Task5ContractPorts.class))
        .forEach(method -> Arrays.stream(method.getAnnotation(Task5ContractPorts.class).value())
            .map(Class::getName)
            .forEach(port -> result.computeIfAbsent(port, ignored -> new TreeSet<>())
                .add(method.getName())));
    return result;
  }

  private static boolean isTask5Port(String name) {
    return !name.equals(
            "dev.christopherbell.admin.commandcenter.metrics.PersistenceIdentityProbe")
        && (name.startsWith("dev.christopherbell.vehicle.")
        || name.startsWith("dev.christopherbell.location.zip.")
        || name.startsWith("dev.christopherbell.whatsforlunch.restaurant.")
        || name.equals("dev.christopherbell.canesboxtracker.CanesBoxPriceSnapshotRepository")
        || name.startsWith("dev.christopherbell.admin.activity.")
        || name.startsWith("dev.christopherbell.admin.commandcenter.")
        || name.equals("dev.christopherbell.libs.lease.ScheduledCollectorRunStore"));
  }
}
