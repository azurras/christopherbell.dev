package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.util.ClassUtils;

class DomainCollectionManifestLegacyIndexTest {
  private static final List<DomainCollectionManifest.IndexDefinition> MANUAL_ONLY_INDEXES =
      List.of(
          manual("application_migrations", "migration_record", "migration_status_completed",
              false, null, asc("status"), desc("completedAt")),
          manual("application_runtime", "application_lease", "lease_expiry",
              false, null, asc("expiresAt")),
          manual("whatsforlunch", "import_preview", "restaurant_import_preview_expiry",
              false, 0L, asc("expiresOn")),
          manual("whatsforlunch", "import_preview", "restaurant_import_preview_actor_created",
              false, null, asc("actorAccountId"), desc("createdOn")),
          manual("vehicles", "vin_decode_cache", "vehicle_vin_cache_expiry",
              false, 0L, asc("expiresOn")),
          manual("application_runtime", "scheduled_collector_run",
              "scheduled_collector_status_completed", false, null,
              asc("status"), desc("completedOn")),
          manual("content", "post_link_preview_cache", "post_link_preview_cache_expiry",
              false, 0L, asc("expiresOn")),
          manual("content", "post", "void_discovery_new", false, null,
              asc("parentId"), desc("createdOn"), desc("_id"), asc("expiresOn")),
          manual("content", "post", "void_discovery_fading", false, null,
              asc("parentId"), asc("expiresOn"), asc("_id")),
          manual("content", "post", "void_discovery_revived", false, null,
              asc("parentId"), desc("lastExtendedOn"), desc("_id"), asc("expiresOn")),
          manual("content", "post", "void_discovery_topic", false, null,
              asc("topics.canonical"), asc("expiresOn"), asc("rootId")),
          manual("content", "post", "void_people_active_pool", false, null,
              asc("expiresOn"), asc("accountId")),
          manual("content", "post", "void_people_authored_activity", false, null,
              asc("accountId"), asc("expiresOn"), desc("createdOn"), desc("_id")),
          manual("accounts", "account_trust_relationship", "void_people_incoming_block",
              false, null, asc("targetAccountId"), asc("type"), asc("ownerAccountId")),
          manual("accounts", "account", "federation_actor_lookup", false, null,
              asc("status"), asc("federationEnabled"), asc("username")),
          manual("content", "post", "federation_outbound_post_scan", false, null,
              asc("federationOutboundEligible"), asc("createdOn"), asc("_id")),
          manual("federation", "federation_delivery_job",
              "federation_delivery_post_peer_unique", true, null,
              asc("postId"), asc("peerName")),
          manual("federation", "federation_delivery_job", "federation_delivery_due",
              false, null, asc("state"), asc("nextAttemptOn"), asc("createdOn")),
          manual("federation", "federation_delivery_job", "federation_delivery_expired_claim",
              false, null, asc("state"), asc("claimUntil")),
          manual("accounts", "account_follow", "account_follow_target", false, null,
              asc("followedAccountId")));

  @Test
  void everyLegacyAnnotationIndexSemanticIsFrozenInTheTargetManifest() throws Exception {
    var documentTypes = mappedDocumentTypes();
    var mapping = new MongoMappingContext();
    mapping.setSimpleTypeHolder(
        MongoCustomConversions.create(adapter -> {}).getSimpleTypeHolder());
    mapping.setInitialEntitySet(documentTypes);
    mapping.afterPropertiesSet();
    var resolver = new MongoPersistentEntityIndexResolver(mapping);
    var definitionsByOwner = DomainCollectionManifest.ALL_KINDS.stream()
        .collect(Collectors.toUnmodifiableMap(
            DomainCollectionManifest.KindDefinition::ownerTypeName,
            definition -> definition));
    var expectedIndexes = new ArrayList<DomainCollectionManifest.IndexDefinition>();

    var annotationIndexCount = 0;
    for (var documentType : documentTypes) {
      var kind = definitionsByOwner.get(documentType.getName());
      assertThat(kind).as("manifest kind for %s", documentType.getName()).isNotNull();
      for (var legacyIndex : resolver.resolveIndexFor(documentType)) {
        annotationIndexCount++;
        var options = new Document(legacyIndex.getIndexOptions());
        var legacyKeys = new Document(legacyIndex.getIndexKeys());
        var legacyName = Optional.ofNullable(options.remove("name"))
            .map(Object::toString)
            .filter(name -> !name.isBlank())
            .filter(name -> !isGeneratedSingleFieldName(name, legacyKeys))
            .orElse(null);
        var unique = Boolean.TRUE.equals(options.remove("unique"));
        var sparse = Boolean.TRUE.equals(options.remove("sparse"));
        var expiry = options.remove("expireAfterSeconds");
        assertThat(options).as("unsupported legacy index options for %s", documentType.getName())
            .isEmpty();
        var keys = remapKeys(legacyKeys);
        expectedIndexes.add(new DomainCollectionManifest.IndexDefinition(
            kind.collection(),
            Optional.of(kind.kind()),
            canonicalIndexName(kind.kind(), legacyName, keys),
            keys,
            unique,
            false,
            partialFilter(kind.kind(), sparse, keys),
            expiry instanceof Number seconds ? Optional.of(seconds.longValue()) : Optional.empty(),
            Optional.empty()));
      }
    }
    assertThat(annotationIndexCount).isEqualTo(92);
    assertThat(MANUAL_ONLY_INDEXES).hasSize(20);
    expectedIndexes.addAll(MANUAL_ONLY_INDEXES);
    assertThat(DomainCollectionManifest.ALL_INDEXES.stream()
        .filter(index -> index.kind().isPresent()))
        .containsExactlyInAnyOrderElementsOf(expectedIndexes);
  }

  private static HashSet<Class<?>> mappedDocumentTypes() {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(
        org.springframework.data.mongodb.core.mapping.Document.class));
    var classLoader = DomainCollectionManifestLegacyIndexTest.class.getClassLoader();
    var types = new HashSet<Class<?>>();
    for (var candidate : scanner.findCandidateComponents("dev.christopherbell")) {
      types.add(ClassUtils.resolveClassName(candidate.getBeanClassName(), classLoader));
    }
    return types;
  }

  private static List<DomainCollectionManifest.IndexKey> remapKeys(Document legacyKeys) {
    return legacyKeys.entrySet().stream()
        .map(entry -> new DomainCollectionManifest.IndexKey(
            entry.getKey().equals("_id") ? "_id.legacyId" : "payload." + entry.getKey(),
            ((Number) entry.getValue()).intValue()))
        .toList();
  }

  private static boolean isGeneratedSingleFieldName(String name, Document legacyKeys) {
    return legacyKeys.size() == 1 && name.equals(legacyKeys.keySet().iterator().next());
  }

  private static DomainCollectionManifest.IndexDefinition manual(
      String collection,
      String kind,
      String legacyName,
      boolean unique,
      Long expireAfterSeconds,
      DomainCollectionManifest.IndexKey... keys) {
    return new DomainCollectionManifest.IndexDefinition(
        collection,
        Optional.of(kind),
        canonicalIndexName(kind, legacyName, List.of(keys)),
        List.of(keys),
        unique,
        false,
        Map.of("_kind", kind),
        Optional.ofNullable(expireAfterSeconds),
        Optional.empty());
  }

  private static DomainCollectionManifest.IndexKey asc(String legacyPath) {
    return mappedKey(legacyPath, 1);
  }

  private static DomainCollectionManifest.IndexKey desc(String legacyPath) {
    return mappedKey(legacyPath, -1);
  }

  private static DomainCollectionManifest.IndexKey mappedKey(String legacyPath, int direction) {
    return new DomainCollectionManifest.IndexKey(
        legacyPath.equals("_id") ? "_id.legacyId" : "payload." + legacyPath,
        direction);
  }

  private static Map<String, Object> partialFilter(
      String kind, boolean legacySparse, List<DomainCollectionManifest.IndexKey> keys) {
    return legacySparse
        ? Map.of("$and", List.of(
            Map.of("_kind", kind),
            Map.of(keys.getFirst().path(), Map.of("$exists", true))))
        : Map.of("_kind", kind);
  }

  private static String canonicalIndexName(
      String kind, String legacyName, List<DomainCollectionManifest.IndexKey> keys) {
    var suffix = legacyName == null
        ? keys.stream().map(DomainCollectionManifestLegacyIndexTest::indexNameToken)
            .collect(Collectors.joining("__"))
        : legacyName;
    var canonical = kind + "__" + suffix;
    if (canonical.length() <= 120) {
      return canonical;
    }
    return canonical.substring(0, 102) + "__" + sha256(canonical).substring(0, 16);
  }

  private static String indexNameToken(DomainCollectionManifest.IndexKey key) {
    var path = key.path();
    if (path.startsWith("payload.")) {
      path = path.substring("payload.".length());
    } else if (path.equals("_id.legacyId")) {
      path = "_id";
    }
    return path.replaceAll("[^A-Za-z0-9_]+", "_")
        + (key.direction() == 1 ? "_asc" : "_desc");
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }
}
