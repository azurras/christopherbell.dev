package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
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
class V005EnsureVoidPeopleDiscoveryIndexesTest {
  @Mock private MongoTemplate mongo;
  @Mock private IndexOperations postIndexes;
  @Mock private IndexOperations trustIndexes;

  @Test
  void identityAndChecksumAreImmutableReviewedValues() {
    var migration = new V005EnsureVoidPeopleDiscoveryIndexes();

    assertThat(migration.id()).isEqualTo("005-ensure-void-people-discovery-indexes");
    assertThat(migration.checksum()).hasSize(64);
    assertThat(migration.description()).isNotBlank();
  }

  @Test
  void applyEnsuresNamedPostAndIncomingBlockIndexes() {
    when(mongo.indexOps("posts")).thenReturn(postIndexes);
    when(mongo.indexOps("account_trust_relationships")).thenReturn(trustIndexes);

    new V005EnsureVoidPeopleDiscoveryIndexes().apply(mongo);

    var postDefinitions = ArgumentCaptor.forClass(IndexDefinition.class);
    verify(postIndexes, times(3)).createIndex(postDefinitions.capture());
    assertThat(postDefinitions.getAllValues())
        .extracting(index -> index.getIndexOptions().getString("name"))
        .containsExactlyInAnyOrder(
            "void_people_active_pool",
            "void_people_authored_activity",
            "void_people_kept_alive_activity");
    var trustDefinition = ArgumentCaptor.forClass(IndexDefinition.class);
    verify(trustIndexes).createIndex(trustDefinition.capture());
    assertThat(trustDefinition.getValue().getIndexOptions().getString("name"))
        .isEqualTo("void_people_incoming_block");
    assertThat(trustDefinition.getValue().getIndexKeys().keySet())
        .containsExactly("targetAccountId", "type", "ownerAccountId");
  }
}
