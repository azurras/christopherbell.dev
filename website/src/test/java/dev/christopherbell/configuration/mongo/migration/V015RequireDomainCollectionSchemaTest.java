package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class V015RequireDomainCollectionSchemaTest {
  @Test
  void requiresTheExactPublishedManifestBeforeStartupMigrationCompletes() {
    var mongo = mock(MongoTemplate.class);
    var ledger = mock(DomainCollectionCutoverLedger.class);
    var migration = new V015RequireDomainCollectionSchema(ledger);

    assertThat(migration.id()).isEqualTo("015-require-domain-collection-schema");
    assertThat(migration.checksum()).isEqualTo(DomainCollectionManifest.DIGEST);
    assertThat(migration.description()).isEqualTo("Require the published 14-collection schema");
    assertThatCode(() -> migration.apply(mongo)).doesNotThrowAnyException();
    verify(ledger).requireTargetActive();
  }
}
