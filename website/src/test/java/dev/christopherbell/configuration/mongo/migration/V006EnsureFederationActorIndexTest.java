package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

@ExtendWith(MockitoExtension.class)
class V006EnsureFederationActorIndexTest {
  @Mock private MongoTemplate mongo;
  @Mock private IndexOperations accountIndexes;

  @Test
  void identityAndChecksumAreImmutableReviewedValues() {
    var migration = new V006EnsureFederationActorIndex();

    assertThat(migration.id()).isEqualTo("006-ensure-federation-actor-index");
    assertThat(migration.checksum()).hasSize(64);
    assertThat(migration.description()).isNotBlank();
  }

  @Test
  void applyEnsuresTheNamedActiveConsentedUsernameLookup() {
    when(mongo.indexOps("accounts")).thenReturn(accountIndexes);

    new V006EnsureFederationActorIndex().apply(mongo);

    var definition = ArgumentCaptor.forClass(IndexDefinition.class);
    verify(accountIndexes).createIndex(definition.capture());
    assertThat(definition.getValue().getIndexOptions().getString("name"))
        .isEqualTo("federation_actor_lookup");
    assertThat(definition.getValue().getIndexKeys().keySet())
        .containsExactly("status", "federationEnabled", "username");
  }
}
