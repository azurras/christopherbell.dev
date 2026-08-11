package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class V015RequireDomainCollectionSchemaTest {
  @Test
  void requiresTheExactPublishedManifestBeforeStartupMigrationCompletes() {
    var mongo = mock(MongoTemplate.class);
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations")))
        .thenReturn(new Document("_id", new Document("kind", "domain_collection_cutover")
            .append("legacyId", "domain-collection-cutover"))
            .append("_kind", "domain_collection_cutover")
            .append("schemaVersion", 1)
            .append("payload", new Document("state", "TARGET_ACTIVE")
                .append("manifestDigest", DomainCollectionManifest.DIGEST)
                .append("completed", true)
                .append("revision", 1)));
    var migration = new V015RequireDomainCollectionSchema();

    assertThat(migration.id()).isEqualTo("015-require-domain-collection-schema");
    assertThat(migration.checksum()).isEqualTo(DomainCollectionManifest.DIGEST);
    assertThat(migration.description()).isEqualTo("Require the published 14-collection schema");
    assertThatCode(() -> migration.apply(mongo)).doesNotThrowAnyException();
  }
}
