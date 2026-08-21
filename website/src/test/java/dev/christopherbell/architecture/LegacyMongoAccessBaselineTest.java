package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LegacyMongoAccessBaselineTest {
  private static final Pattern DOCUMENT_USAGE = Pattern.compile(
      "@(?:org\\.springframework\\.data\\.mongodb\\.core\\.mapping\\.)?Document\\b");
  private static final Pattern MONGO_REPOSITORY_USAGE =
      Pattern.compile("\\bMongoRepository\\s*<");
  private static final Pattern MONGO_TEMPLATE_USAGE = Pattern.compile("\\bMongoTemplate\\b");
  private static final Pattern MONGO_COLLECTION_USAGE = Pattern.compile("\\bMongoCollection\\b");
  private static final List<Pattern> DIRECT_MONGO_CONSUMER_USAGE = List.of(
      Pattern.compile("\\bDomainMongoOperationsFactory\\b"),
      Pattern.compile("\\bKindScopedMongoOperations\\b"),
      Pattern.compile("\\bMongoTemplate\\b"),
      Pattern.compile("\\bMongoOperations\\b"),
      Pattern.compile("\\bMongoRepository\\b"));
  private static final List<Path> PRODUCTION_SOURCE_ROOTS = List.of(
      repositoryRoot().resolve("website/src/main/java"),
      repositoryRoot().resolve("cbell-lib/src/main/java"));

  private static final Set<String> APPROVED_DIRECT_MONGO_INFRASTRUCTURE = Set.of(
      "dev.christopherbell.admin.commandcenter.metrics.MongoDatabaseConnectivityProbe",
      "dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory",
      "dev.christopherbell.configuration.mongo.domain.MongoKindScopedOperations",
      "dev.christopherbell.configuration.mongo.migration.ApplicationMigration",
      "dev.christopherbell.configuration.mongo.migration.DomainCollectionCutoverLedger",
      "dev.christopherbell.configuration.mongo.migration.MongoMigrationRunner",
      "dev.christopherbell.configuration.mongo.migration.V001EnsureMigrationInfrastructure",
      "dev.christopherbell.configuration.mongo.migration.V002EnsureRestaurantImportPreviewIndexes",
      "dev.christopherbell.configuration.mongo.migration.V003EnsureVinPreviewCollectorIndexes",
      "dev.christopherbell.configuration.mongo.migration.V004EnsureVoidDiscoveryIndexes",
      "dev.christopherbell.configuration.mongo.migration.V005EnsureVoidPeopleDiscoveryIndexes",
      "dev.christopherbell.configuration.mongo.migration.V006EnsureFederationActorIndex",
      "dev.christopherbell.configuration.mongo.migration.V007EnsureFederationOutboundIndexes",
      "dev.christopherbell.configuration.mongo.migration.V008RemoveAccountApprovalFields",
      "dev.christopherbell.configuration.mongo.migration.V009MoveSocialRelationshipsToEdges",
      "dev.christopherbell.configuration.mongo.migration.V010BackfillPostExpirationMetrics",
      "dev.christopherbell.configuration.mongo.migration.V011HardenWhatsForLunchData",
      "dev.christopherbell.configuration.mongo.migration.V012RetainSharedFolderWork",
      "dev.christopherbell.configuration.mongo.migration.V013ConvertRestaurantRatingsToVotes",
      "dev.christopherbell.configuration.mongo.migration.V014ConsolidateMusicRuntimeState",
      "dev.christopherbell.configuration.mongo.migration.V015RequireDomainCollectionSchema");
  private static final Set<String> POSTGRESQL_MIGRATION_INFRASTRUCTURE = Set.of(
      "dev.christopherbell.configuration.persistence.migration.DirectMigrationIdentityProbe",
      "dev.christopherbell.configuration.persistence.migration.MongoFinalizationFreezeGuard",
      "dev.christopherbell.configuration.persistence.migration.MongoMigrationSourceReader",
      "dev.christopherbell.configuration.persistence.migration.PostgresqlMigrationCli",
      "dev.christopherbell.configuration.persistence.migration.PostgresqlMigrationSourceSnapshotCli",
      "dev.christopherbell.configuration.persistence.migration.ProductionFinalizationFreezeGuard");

  @Test
  void runtimeDomainModelsDoNotOwnPhysicalMongoCollections() throws IOException {
    assertThat(ownerTypesMatching(DOCUMENT_USAGE)).isEmpty();
  }

  @Test
  void runtimeDomainPortsDoNotInheritSpringDataRepositories() throws IOException {
    assertThat(ownerTypesMatching(MONGO_REPOSITORY_USAGE)).isEmpty();
  }

  @Test
  void directMongoUseIsRestrictedToApprovedInfrastructure() throws IOException {
    assertThat(ownerTypesMatching(MONGO_TEMPLATE_USAGE))
        .containsExactlyInAnyOrderElementsOf(APPROVED_DIRECT_MONGO_INFRASTRUCTURE);
    assertThat(ownerTypesMatching(MONGO_COLLECTION_USAGE)).isEmpty();
  }

  @Test
  void everyDiscoveredDirectMongoConsumerHasOneExplicitTransitionClassification()
      throws IOException {
    var classified = new java.util.EnumMap<DirectMongoConsumerKind, Set<String>>(
        DirectMongoConsumerKind.class);
    Arrays.stream(DirectMongoConsumerKind.values())
        .forEach(kind -> classified.put(kind, new TreeSet<>()));
    var discovered = ownerTypesMatchingAny(DIRECT_MONGO_CONSUMER_USAGE);
    for (var owner : discovered) {
      var source = sourceFor(owner);
      var kind = owner.startsWith("dev.christopherbell.configuration.mongo.migration.")
          ? DirectMongoConsumerKind.MIGRATION_READER
          : source.contains("@MongoPersistence")
              || owner.startsWith("dev.christopherbell.configuration.mongo.domain.")
              ? DirectMongoConsumerKind.TRANSITION_ADAPTER
              : DirectMongoConsumerKind.RETIREMENT_DELETION;
      classified.get(kind).add(owner);
    }

    assertThat(classified.get(DirectMongoConsumerKind.TRANSITION_ADAPTER)).isNotEmpty();
    assertThat(classified.get(DirectMongoConsumerKind.MIGRATION_READER)).isNotEmpty();
    assertThat(classified.get(DirectMongoConsumerKind.RETIREMENT_DELETION)).isEmpty();
    assertThat(classified.values().stream().flatMap(Set::stream).collect(
        java.util.stream.Collectors.toSet()))
        .containsExactlyInAnyOrderElementsOf(discovered);
  }

  @Test
  void compiledRuntimeDependenciesCannotBypassKindScopedMongoOperations() {
    var classes = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.christopherbell");

    var approved = new java.util.HashSet<>(APPROVED_DIRECT_MONGO_INFRASTRUCTURE);
    approved.addAll(POSTGRESQL_MIGRATION_INFRASTRUCTURE);
    new MongoPersistenceBoundaryRules(
        "dev.christopherbell", approved)
        .zeroBypassRule()
        .check(classes);
  }

  private static Set<String> ownerTypesMatching(Pattern pattern) throws IOException {
    var owners = new TreeSet<String>();
    for (var sourceRoot : PRODUCTION_SOURCE_ROOTS) {
      try (var files = Files.walk(sourceRoot)) {
        files.filter(path -> path.toString().endsWith(".java"))
            .filter(path -> matches(path, pattern))
            .map(sourceRoot::relativize)
            .map(Path::toString)
            .map(path -> path.substring(0, path.length() - ".java".length()))
            .map(path -> path.replace('\\', '.').replace('/', '.'))
            .forEach(owners::add);
      }
    }
    return Set.copyOf(owners);
  }

  private static Set<String> ownerTypesMatchingAny(List<Pattern> patterns) throws IOException {
    var owners = new TreeSet<String>();
    for (var pattern : patterns) {
      owners.addAll(ownerTypesMatching(pattern));
    }
    return Set.copyOf(owners);
  }

  private static String sourceFor(String owner) throws IOException {
    var relative = Path.of(owner.replace('.', '/') + ".java");
    for (var root : PRODUCTION_SOURCE_ROOTS) {
      var source = root.resolve(relative);
      if (Files.isRegularFile(source)) return Files.readString(source);
    }
    throw new IllegalStateException("Cannot locate production source for " + owner);
  }

  private static boolean matches(Path path, Pattern pattern) {
    try {
      return pattern.matcher(Files.readString(path)).find();
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot read source file.", failure);
    }
  }

  private static Path repositoryRoot() {
    var current = Path.of("").toAbsolutePath().normalize();
    if (Files.isDirectory(current.resolve(".github"))) {
      return current;
    }
    var parent = current.getParent();
    if (parent != null && Files.isDirectory(parent.resolve(".github"))) {
      return parent;
    }
    throw new IllegalStateException("Cannot locate repository root.");
  }

  private enum DirectMongoConsumerKind {
    TRANSITION_ADAPTER,
    MIGRATION_READER,
    RETIREMENT_DELETION
  }
}
