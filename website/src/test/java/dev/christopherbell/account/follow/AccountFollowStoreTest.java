package dev.christopherbell.account.follow;

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
class AccountFollowStoreTest {
  @Mock private MongoTemplate mongo;

  @Test
  void duplicateFollowAndUnfollowRetriesAreIdempotent() {
    when(mongo.insert(any(Document.class), eq("accounts")))
        .thenAnswer(invocation -> invocation.getArgument(0))
        .thenThrow(new DuplicateKeyException("duplicate edge"));
    when(mongo.remove(any(Query.class), eq(Document.class), eq("accounts")))
        .thenReturn(DeleteResult.acknowledged(1))
        .thenReturn(DeleteResult.acknowledged(0));
    var store = new MongoAccountFollowStore(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo));

    assertThat(store.follow("self", "target", Instant.EPOCH).created()).isTrue();
    assertThat(store.follow("self", "target", Instant.EPOCH).created()).isFalse();
    assertThat(store.unfollow("self", "target").removed()).isTrue();
    assertThat(store.unfollow("self", "target").removed()).isFalse();
  }
}
