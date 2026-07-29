package dev.christopherbell.account.follow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith(MockitoExtension.class)
class AccountFollowStoreTest {
  @Mock private MongoTemplate mongo;

  @Test
  void duplicateFollowAndUnfollowRetriesAreIdempotent() {
    when(mongo.upsert(any(Query.class), any(UpdateDefinition.class), eq(AccountFollow.class)))
        .thenReturn(UpdateResult.acknowledged(0, 0L, new BsonString("edge")))
        .thenReturn(UpdateResult.acknowledged(1, 0L, null));
    when(mongo.remove(any(Query.class), eq(AccountFollow.class)))
        .thenReturn(DeleteResult.acknowledged(1))
        .thenReturn(DeleteResult.acknowledged(0));
    var store = new AccountFollowStore(mongo);

    assertThat(store.follow("self", "target", Instant.EPOCH).created()).isTrue();
    assertThat(store.follow("self", "target", Instant.EPOCH).created()).isFalse();
    assertThat(store.unfollow("self", "target").removed()).isTrue();
    assertThat(store.unfollow("self", "target").removed()).isFalse();
  }
}
