package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
  private static final List<Path> PRODUCTION_SOURCE_ROOTS = List.of(
      repositoryRoot().resolve("website/src/main/java"),
      repositoryRoot().resolve("cbell-lib/src/main/java"));

  private static final Set<String> TEMPORARY_DOCUMENT_BASELINE = Set.of(
      "dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot",
      "dev.christopherbell.configuration.mongo.migration.MigrationRecord",
      "dev.christopherbell.libs.mongo.lease.MongoLeaseDocument",
      "dev.christopherbell.libs.mongo.lease.ScheduledCollectorRun",
      "dev.christopherbell.location.model.ZipCoordinate",
      "dev.christopherbell.location.model.ZipCoordinateImportState",
      "dev.christopherbell.music.catalog.MusicTrack",
      "dev.christopherbell.music.library.MusicPlaylist",
      "dev.christopherbell.music.metadata.MusicMetadataEdit",
      "dev.christopherbell.music.radio.MusicRadioHistoryEvent",
      "dev.christopherbell.music.radio.MusicRuntimeStateDocument",
      "dev.christopherbell.music.security.MusicAccessAttempt",
      "dev.christopherbell.sharedfolder.audit.SharedFolderAuditEvent",
      "dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceLeaseDocument",
      "dev.christopherbell.sharedfolder.media.MediaJob",
      "dev.christopherbell.sharedfolder.radio.SharedFolderRadioDocument",
      "dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleItem",
      "dev.christopherbell.sharedfolder.service.SharedFolderMutationRecovery",
      "dev.christopherbell.sharedfolder.upload.SharedFolderUploadSession",
      "dev.christopherbell.vehicle.model.Vehicle",
      "dev.christopherbell.vehicle.model.VehicleVinDecodeCache",
      "dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState",
      "dev.christopherbell.vehicle.randomvin.model.RandomVinImportState",
      "dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewDocument",
      "dev.christopherbell.whatsforlunch.restaurant.model.DailyLunchPicks",
      "dev.christopherbell.whatsforlunch.restaurant.model.Restaurant",
      "dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite",
      "dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportState",
      "dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVote",
      "dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreference",
      "dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession");

  private static final Set<String> TEMPORARY_MONGO_REPOSITORY_BASELINE = Set.of(
      "dev.christopherbell.canesboxtracker.CanesBoxPriceSnapshotRepository",
      "dev.christopherbell.location.zip.ZipCoordinateImportStateRepository",
      "dev.christopherbell.location.zip.ZipCoordinateRepository",
      "dev.christopherbell.music.catalog.MusicTrackRepository",
      "dev.christopherbell.music.library.MusicPlaylistRepository",
      "dev.christopherbell.music.metadata.MusicMetadataEditRepository",
      "dev.christopherbell.music.radio.MusicRadioHistoryRepository",
      "dev.christopherbell.sharedfolder.audit.SharedFolderAuditRepository",
      "dev.christopherbell.sharedfolder.media.MediaJobRepository",
      "dev.christopherbell.sharedfolder.radio.SharedFolderRadioRepository",
      "dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleRepository",
      "dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryRepository",
      "dev.christopherbell.sharedfolder.upload.SharedFolderUploadSessionRepository",
      "dev.christopherbell.vehicle.core.VehicleRepository",
      "dev.christopherbell.vehicle.nhtsa.decode.VehicleVinDecodeCacheRepository",
      "dev.christopherbell.vehicle.nhtsa.enrichment.NhtsaVinImportStateRepository",
      "dev.christopherbell.vehicle.randomvin.importing.RandomVinImportStateRepository",
      "dev.christopherbell.whatsforlunch.restaurant.DailyLunchPicksRepository",
      "dev.christopherbell.whatsforlunch.restaurant.favorite.RestaurantFavoriteRepository",
      "dev.christopherbell.whatsforlunch.restaurant.preference.WhatsForLunchPreferenceRepository",
      "dev.christopherbell.whatsforlunch.restaurant.RestaurantImportStateRepository",
      "dev.christopherbell.whatsforlunch.restaurant.RestaurantRepository",
      "dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionRepository",
      "dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteRepository");

  private static final Set<String> APPROVED_DIRECT_MONGO_INFRASTRUCTURE = Set.of(
      "dev.christopherbell.admin.commandcenter.metrics.CommandCenterMetricsService",
      "dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory",
      "dev.christopherbell.configuration.mongo.domain.MongoKindScopedOperations",
      "dev.christopherbell.configuration.mongo.migration.ApplicationMigration",
      "dev.christopherbell.configuration.mongo.migration.MigrationStateStore",
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
      "dev.christopherbell.music.catalog.MusicCatalogConfiguration");

  private static final Set<String> TEMPORARY_DIRECT_MONGO_BASELINE = Set.of(
      "dev.christopherbell.libs.mongo.lease.MongoLeaseService",
      "dev.christopherbell.libs.mongo.lease.ScheduledCollectorCoordinator",
      "dev.christopherbell.music.catalog.MusicCatalog",
      "dev.christopherbell.music.library.MusicLibraryService",
      "dev.christopherbell.music.radio.MusicRuntimeStateStore",
      "dev.christopherbell.music.security.MusicAccessAuditQueryService",
      "dev.christopherbell.music.security.MusicAccessAuditRecorder",
      "dev.christopherbell.sharedfolder.audit.SharedFolderAuditQueryService",
      "dev.christopherbell.sharedfolder.maintenance.MongoSharedFolderMaintenanceLeaseStore",
      "dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewStore",
      "dev.christopherbell.whatsforlunch.restaurant.RestaurantDuplicateQueryRepository",
      "dev.christopherbell.whatsforlunch.restaurant.RestaurantInventoryQueryRepository",
      "dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionMutationStore",
      "dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteQueryRepository");

  @Test
  void legacyDocumentAnnotationsCannotGrowBeforeTheirAdaptersReplaceThem() throws IOException {
    assertThat(ownerTypesMatching(DOCUMENT_USAGE))
        .containsExactlyInAnyOrderElementsOf(TEMPORARY_DOCUMENT_BASELINE);
  }

  @Test
  void legacyMongoRepositoriesCannotGrowBeforeTheirAdaptersReplaceThem() throws IOException {
    assertThat(ownerTypesMatching(MONGO_REPOSITORY_USAGE))
        .containsExactlyInAnyOrderElementsOf(TEMPORARY_MONGO_REPOSITORY_BASELINE);
  }

  @Test
  void directMongoTemplateUseCannotGrowAndRawMongoCollectionUseRemainsZero() throws IOException {
    var expectedMongoTemplateOwners = new TreeSet<>(APPROVED_DIRECT_MONGO_INFRASTRUCTURE);
    expectedMongoTemplateOwners.addAll(TEMPORARY_DIRECT_MONGO_BASELINE);

    assertThat(ownerTypesMatching(MONGO_TEMPLATE_USAGE))
        .containsExactlyInAnyOrderElementsOf(expectedMongoTemplateOwners);
    assertThat(ownerTypesMatching(MONGO_COLLECTION_USAGE)).isEmpty();
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
}
