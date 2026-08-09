package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.util.ClassUtils;

class MongoCollectionCatalogTest {
  private static final Path CATALOG =
      repositoryRoot().resolve("docs/operations/mongodb-collection-catalog.md");
  private static final Pattern PHYSICAL_NAME = Pattern.compile("[a-z][a-z0-9_]*");
  private static final Set<String> VALID_ROLES = Set.of(
      "audit", "cache", "edge", "entity", "event-history", "job", "lease",
      "preference", "singleton-state");
  private static final Set<String> VALID_SENSITIVITY = Set.of(
      "audit", "confidential", "internal", "public-reference", "security", "user");
  private static final Set<String> VALID_STATUSES = Set.of("active", "legacy-named");
  private static final Set<String> MANUAL_COLLECTION_REFERENCES = Set.of(
      "account_follows",
      "account_trust_relationships",
      "accounts",
      "browser_sessions",
      "conversation_archive_states",
      "federation_delivery_jobs",
      "hidden_post_threads",
      "messages",
      "notification_delivery_guards",
      "notification_preferences",
      "notification_rate_limits",
      "notifications",
      "post_likes",
      "posts",
      "shared_folder_media_jobs",
      "shared_folder_upload_sessions",
      "whatsforlunch",
      "whatsforlunch_favorites",
      "whatsforlunch_preferences",
      "whatsforlunch_ratings",
      "whatsforlunch_sessions");

  @Test
  void catalogEntriesAreCompleteAndValid() throws IOException {
    var entries = readCatalog();

    assertThat(entries).hasSize(51);
    assertThat(entries).extracting(CatalogEntry::physicalName).doesNotHaveDuplicates();
    assertThat(entries).allSatisfy(entry -> {
      assertThat(entry.physicalName()).matches(PHYSICAL_NAME);
      assertThat(entry.logicalName()).isNotBlank();
      assertThat(entry.ownerAndMapping()).isNotBlank();
      assertThat(entry.role()).isIn(VALID_ROLES);
      assertThat(entry.cardinalityAndRetention()).isNotBlank();
      assertThat(entry.indexContract()).isNotBlank();
      assertThat(entry.sensitivity()).isIn(VALID_SENSITIVITY);
      assertThat(entry.status()).isIn(VALID_STATUSES);
    });
  }

  @Test
  void everyMappedAndManualCollectionIsCataloged() throws IOException {
    var expected = new TreeSet<>(mappedCollectionNames());
    expected.addAll(MANUAL_COLLECTION_REFERENCES);

    assertThat(readCatalog())
        .extracting(CatalogEntry::physicalName)
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  private static Set<String> mappedCollectionNames() {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Document.class));
    var classLoader = MongoCollectionCatalogTest.class.getClassLoader();
    var names = new TreeSet<String>();
    for (var candidate : scanner.findCandidateComponents("dev.christopherbell")) {
      var className = candidate.getBeanClassName();
      assertThat(className).isNotBlank();
      var documentType = ClassUtils.resolveClassName(className, classLoader);
      var document = AnnotatedElementUtils.findMergedAnnotation(documentType, Document.class);
      assertThat(document).as("@Document on %s", className).isNotNull();
      assertThat(document.collection()).as("explicit collection for %s", className).isNotBlank();
      names.add(document.collection());
    }
    return names;
  }

  private static List<CatalogEntry> readCatalog() throws IOException {
    try (var lines = Files.lines(CATALOG)) {
      return lines
          .filter(line -> line.startsWith("| `"))
          .map(MongoCollectionCatalogTest::parseCatalogRow)
          .toList();
    }
  }

  private static CatalogEntry parseCatalogRow(String line) {
    var cells = Arrays.stream(line.substring(1, line.length() - 1).split("\\|", -1))
        .map(String::trim)
        .toList();
    assertThat(cells).as("catalog row: %s", line).hasSize(8);
    return new CatalogEntry(
        unquoteCode(cells.get(0)),
        cells.get(1),
        cells.get(2),
        cells.get(3),
        cells.get(4),
        cells.get(5),
        cells.get(6),
        cells.get(7));
  }

  private static String unquoteCode(String value) {
    assertThat(value).startsWith("`").endsWith("`");
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
      String physicalName,
      String logicalName,
      String ownerAndMapping,
      String role,
      String cardinalityAndRetention,
      String indexContract,
      String sensitivity,
      String status) {}
}
