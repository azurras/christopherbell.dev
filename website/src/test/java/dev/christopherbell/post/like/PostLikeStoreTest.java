package dev.christopherbell.post.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class PostLikeStoreTest {
  @Mock private MongoTemplate mongo;

  @Test
  void repeatedLikeReportsOnlyTheFirstEdgeAsCreated() {
    var store = new MongoPostLikeStore(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo));
    when(mongo.insert(any(Document.class), eq("content")))
        .thenAnswer(invocation -> invocation.getArgument(0))
        .thenThrow(new DuplicateKeyException("duplicate edge"));

    assertThat(store.like("post", "account", Instant.EPOCH).created()).isTrue();
    assertThat(store.like("post", "account", Instant.EPOCH).created()).isFalse();
  }

  @Test
  void repeatedUnlikeReportsOnlyTheExistingEdgeAsRemoved() {
    when(mongo.remove(any(Query.class), eq(Document.class), eq("content")))
        .thenReturn(DeleteResult.acknowledged(1))
        .thenReturn(DeleteResult.acknowledged(0));
    var store = new MongoPostLikeStore(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo));

    assertThat(store.unlike("post", "account").removed()).isTrue();
    assertThat(store.unlike("post", "account").removed()).isFalse();
  }
}
