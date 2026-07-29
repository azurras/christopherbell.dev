package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

@ExtendWith(MockitoExtension.class)
class V004EnsureVoidDiscoveryIndexesTest {
  @Mock private MongoTemplate mongo;
  @Mock private IndexOperations postIndexes;

  @Test
  void identityAndChecksumAreImmutableReviewedValues() {
    var migration = new V004EnsureVoidDiscoveryIndexes();

    assertThat(migration.id()).isEqualTo("004-ensure-void-discovery-indexes");
    assertThat(migration.checksum()).hasSize(64);
    assertThat(migration.description()).isNotBlank();
  }

  @Test
  void applyEnsuresAllNamedBoundedQueryIndexes() {
    when(mongo.indexOps("posts")).thenReturn(postIndexes);

    new V004EnsureVoidDiscoveryIndexes().apply(mongo);

    var indexes = ArgumentCaptor.forClass(IndexDefinition.class);
    verify(postIndexes, times(4)).createIndex(indexes.capture());
    var names = indexes.getAllValues().stream()
        .map(index -> index.getIndexOptions().getString("name"))
        .collect(Collectors.toSet());
    assertThat(names).containsExactlyInAnyOrder(
        "void_discovery_new",
        "void_discovery_fading",
        "void_discovery_revived",
        "void_discovery_topic");
    assertThat(indexes.getAllValues())
        .allSatisfy(index -> assertThat(index.getIndexKeys()).isNotEmpty());
  }
}
