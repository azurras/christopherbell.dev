package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MongoCollectionCatalogTest {
  private static final Path CATALOG =
      repositoryRoot().resolve("docs/operations/mongodb-collection-catalog.md");
  private static final String CATALOG_HEADER =
      "| Physical collection | Owning module | Kind | Legacy source | Schema version | Count | Index contract | Status |";
  private static final String CATALOG_SEPARATOR =
      "| --- | --- | --- | --- | --- | --- | --- | --- |";
  private static final Pattern CANONICAL_NAME = Pattern.compile("[a-z][a-z0-9_]*");
  private static final String MANIFEST_DIGEST =
      "576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24";
  private static final Set<String> EXPECTED_COLLECTIONS = Set.of(
      "accounts", "sessions", "communications", "content", "federation", "music",
      "whatsforlunch", "shared_folder", "vehicles", "location", "canes_box_tracker",
      "application_runtime", "application_migrations", "admin_activity");
  private static final Set<String> APPROVED_MODULES = Set.of(
      "account", "admin", "canesboxtracker", "cbell-lib", "configuration", "federation",
      "location", "message", "music", "notification", "post", "report", "sharedfolder",
      "vehicle", "whatsforlunch");

  @Test
  void catalogMatchesTheExactManifestKindByKind() throws IOException {
    var entries = readCatalog();

    assertThat(entries).containsExactlyElementsOf(expectedEntries());
    assertThat(entries).hasSize(52);
    assertThat(entries.stream().map(CatalogEntry::physicalCollection).collect(Collectors.toSet()))
        .containsExactlyInAnyOrderElementsOf(EXPECTED_COLLECTIONS);
    assertThat(entries.stream().mapToInt(CatalogEntry::kindIndexCount).sum()).isEqualTo(112);
  }

  @Test
  void manifestMatchesTheIndependentCatalogContractKindByKind() {
    assertThat(manifestEntries()).containsExactlyElementsOf(expectedEntries());
    assertThat(DomainCollectionManifest.ALL_COLLECTIONS)
        .containsExactlyInAnyOrderElementsOf(EXPECTED_COLLECTIONS);
    assertThat(DomainCollectionManifest.DIGEST).isEqualTo(MANIFEST_DIGEST);
  }

  @Test
  void catalogEntriesUseOnlyCanonicalOperationalValues() throws IOException {
    assertThat(readCatalog()).allSatisfy(entry -> {
      assertThat(entry.physicalCollection()).matches(CANONICAL_NAME);
      assertThat(entry.owningModule()).isIn(APPROVED_MODULES);
      assertThat(entry.kind()).matches(CANONICAL_NAME);
      assertThat(entry.legacySource())
          .matches(source -> source.equals("cutover-created") || CANONICAL_NAME.matcher(source).matches());
      assertThat(entry.schemaVersion()).isPositive();
      assertThat(entry.count()).isEqualTo("runtime inventory");
      assertThat(entry.manifestDigest()).isEqualTo(MANIFEST_DIGEST);
      assertThat(entry.status()).isEqualTo("target");
    });
  }

  @Test
  void catalogRejectsMalformedDataRow(@TempDir Path temporaryDirectory) throws IOException {
    var catalog = temporaryDirectory.resolve("catalog.md");
    Files.writeString(catalog, """
        | Physical collection | Owning module | Kind | Legacy source | Schema version | Count | Index contract | Status |
        | --- | --- | --- | --- | --- | --- | --- | --- |
        | `accounts` | `account` | account | `accounts` | 1 | runtime inventory | 4 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
        """);

    assertThatThrownBy(() -> readCatalog(catalog))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("catalog table row 3 kind");
  }

  private static List<CatalogEntry> expectedEntries() {
    return List.of(
      entry("accounts", "account", "account", "accounts", 4),
      entry("accounts", "account", "account_follow", "account_follows", 2),
      entry("accounts", "account", "account_trust_relationship", "account_trust_relationships", 4),
      entry("accounts", "account", "account_deletion_job", "account_deletion_jobs", 0),
      entry("sessions", "configuration", "browser_session", "browser_sessions", 2),
      entry("sessions", "message", "conversation_archive_state", "conversation_archive_states", 1),
      entry("communications", "message", "message", "messages", 5),
      entry("communications", "notification", "notification", "notifications", 2),
      entry("communications", "notification", "notification_preference", "notification_preferences", 1),
      entry("communications", "notification", "notification_delivery_guard", "notification_delivery_guards", 1),
      entry("communications", "notification", "notification_rate_limit", "notification_rate_limits", 1),
      entry("content", "post", "post", "posts", 13),
      entry("content", "post", "post_like", "post_likes", 1),
      entry("content", "report", "post_report", "post_reports", 5),
      entry("content", "post", "hidden_post_thread", "hidden_post_threads", 3),
      entry("content", "post", "post_link_preview_cache", "post_link_preview_cache", 1),
      entry("federation", "federation", "federation_scan_state", "federation_scan_state", 0),
      entry("federation", "federation", "federation_delivery_job", "federation_delivery_jobs", 3),
      entry("music", "music", "music_track", "music_tracks", 4),
      entry("music", "music", "music_playlist", "music_playlists", 1),
      entry("music", "music", "music_metadata_edit", "music_metadata_edits", 2),
      entry("music", "music", "music_runtime_state", "music_runtime_state", 0),
      entry("music", "music", "music_radio_history", "music_radio_history", 2),
      entry("music", "music", "music_access_attempt", "music_access_attempts", 1),
      entry("whatsforlunch", "whatsforlunch", "restaurant", "whatsforlunch", 6),
      entry("whatsforlunch", "whatsforlunch", "vote", "whatsforlunch_ratings", 2),
      entry("whatsforlunch", "whatsforlunch", "favorite", "whatsforlunch_favorites", 3),
      entry("whatsforlunch", "whatsforlunch", "preference", "whatsforlunch_preferences", 0),
      entry("whatsforlunch", "whatsforlunch", "session", "whatsforlunch_sessions", 4),
      entry("whatsforlunch", "whatsforlunch", "daily_picks", "whatsforlunch_daily_picks", 0),
      entry("whatsforlunch", "whatsforlunch", "import_state", "restaurant_import_state", 0),
      entry("whatsforlunch", "whatsforlunch", "import_preview", "restaurant_import_previews", 2),
      entry("shared_folder", "sharedfolder", "audit_event", "shared_folder_audit", 9),
      entry("shared_folder", "sharedfolder", "maintenance_lease", "shared_folder_maintenance_leases", 0),
      entry("shared_folder", "sharedfolder", "media_job", "shared_folder_media_jobs", 8),
      entry("shared_folder", "sharedfolder", "mutation_recovery", "shared_folder_mutation_recoveries", 2),
      entry("shared_folder", "sharedfolder", "radio_state", "shared_folder_radio", 0),
      entry("shared_folder", "sharedfolder", "recycle_item", "shared_folder_recycle_items", 3),
      entry("shared_folder", "sharedfolder", "upload_session", "shared_folder_upload_sessions", 5),
      entry("vehicles", "vehicle", "vehicle", "vehicles", 1),
      entry("vehicles", "vehicle", "vin_decode_cache", "vehicle_vin_decode_cache", 1),
      entry("vehicles", "vehicle", "nhtsa_import_state", "vehicle_import_state", 0),
      entry("vehicles", "vehicle", "random_vin_import_state", "vehicle_import_state", 0),
      entry("location", "location", "zip_coordinate", "location_zip_coordinates", 0),
      entry("location", "location", "zip_import_state", "zip_coordinate_import_state", 0),
      entry("canes_box_tracker", "canesboxtracker", "price_snapshot", "canes_box_price_snapshots", 0),
      entry("application_runtime", "cbell-lib", "application_lease", "application_leases", 1),
      entry("application_runtime", "cbell-lib", "scheduled_collector_run", "scheduled_collector_runs", 1),
      entry("application_migrations", "configuration", "migration_record", "application_migrations", 1),
      entry("application_migrations", "configuration", "domain_collection_cutover", "cutover-created", 0),
      entry("admin_activity", "admin", "admin_activity", "admin_activity", 4),
      entry("admin_activity", "admin", "pending_action", "command_center_pending_actions", 0));
  }

  private static CatalogEntry entry(
      String physicalCollection,
      String owningModule,
      String kind,
      String legacySource,
      int kindIndexCount) {
    return new CatalogEntry(
        physicalCollection,
        owningModule,
        kind,
        legacySource,
        1,
        "runtime inventory",
        kindIndexCount,
        MANIFEST_DIGEST,
        "target");
  }

  private static List<CatalogEntry> manifestEntries() {
    return DomainCollectionManifest.ALL_KINDS.stream()
        .map(kind -> new CatalogEntry(
            kind.collection(),
            owningModule(kind.ownerTypeName()),
            kind.kind(),
            kind.legacySource().orElse("cutover-created"),
            kind.schemaVersion(),
            "runtime inventory",
            kind.indexes().size(),
            DomainCollectionManifest.DIGEST,
            "target"))
        .toList();
  }

  private static String owningModule(String ownerTypeName) {
    var prefix = "dev.christopherbell.";
    assertThat(ownerTypeName).startsWith(prefix);
    var relativeName = ownerTypeName.substring(prefix.length());
    if (relativeName.startsWith("libs.")) {
      return "cbell-lib";
    }
    var separator = relativeName.indexOf('.');
    assertThat(separator).isPositive();
    return relativeName.substring(0, separator);
  }

  private static List<CatalogEntry> readCatalog() throws IOException {
    return readCatalog(CATALOG);
  }

  private static List<CatalogEntry> readCatalog(Path catalog) throws IOException {
    var lines = Files.readAllLines(catalog);
    var headerIndex = lines.indexOf(CATALOG_HEADER);
    assertThat(headerIndex).as("catalog header").isGreaterThanOrEqualTo(0);
    assertThat(lines).as("catalog separator").hasSizeGreaterThan(headerIndex + 1);
    assertThat(lines.get(headerIndex + 1)).as("catalog separator").isEqualTo(CATALOG_SEPARATOR);

    var entries = new ArrayList<CatalogEntry>();
    for (var index = headerIndex + 2; index < lines.size(); index++) {
      var line = lines.get(index);
      if (line.isBlank()) {
        return List.copyOf(entries);
      }
      assertThat(line).as("catalog table row %s", index + 1).startsWith("|").endsWith("|");
      entries.add(parseCatalogRow(line, index + 1));
    }
    return List.copyOf(entries);
  }

  private static CatalogEntry parseCatalogRow(String line, int lineNumber) {
    var cells = Arrays.stream(line.substring(1, line.length() - 1).split("\\|", -1))
        .map(String::trim)
        .toList();
    assertThat(cells).as("catalog table row %s: %s", lineNumber, line).hasSize(8);
    var indexCells = cells.get(6).split("; manifest ", -1);
    assertThat(indexCells).as("catalog table row %s index contract", lineNumber).hasSize(2);
    assertThat(indexCells[0]).as("catalog table row %s index count", lineNumber)
        .endsWith(" kind-scoped");
    return new CatalogEntry(
        unquoteCode(cells.get(0), lineNumber, "physical collection"),
        unquoteCode(cells.get(1), lineNumber, "owning module"),
        unquoteCode(cells.get(2), lineNumber, "kind"),
        cells.get(3).equals("cutover-created")
            ? cells.get(3)
            : unquoteCode(cells.get(3), lineNumber, "legacy source"),
        parsePositiveInt(cells.get(4), lineNumber, "schema version"),
        cells.get(5),
        parseNonNegativeInt(
            indexCells[0].substring(0, indexCells[0].length() - " kind-scoped".length()),
            lineNumber,
            "index count"),
        unquoteCode(indexCells[1], lineNumber, "manifest digest"),
        cells.get(7));
  }

  private static int parsePositiveInt(String value, int lineNumber, String field) {
    var parsed = parseNonNegativeInt(value, lineNumber, field);
    assertThat(parsed).as("catalog table row %s %s", lineNumber, field).isPositive();
    return parsed;
  }

  private static int parseNonNegativeInt(String value, int lineNumber, String field) {
    assertThat(value).as("catalog table row %s %s", lineNumber, field).matches("0|[1-9][0-9]*");
    return Integer.parseInt(value);
  }

  private static String unquoteCode(String value, int lineNumber, String field) {
    assertThat(value).as("catalog table row %s %s", lineNumber, field)
        .startsWith("`").endsWith("`");
    return value.substring(1, value.length() - 1);
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
    throw new IllegalStateException("Cannot locate repository root from " + current);
  }

  private record CatalogEntry(
      String physicalCollection,
      String owningModule,
      String kind,
      String legacySource,
      int schemaVersion,
      String count,
      int kindIndexCount,
      String manifestDigest,
      String status) {}
}
