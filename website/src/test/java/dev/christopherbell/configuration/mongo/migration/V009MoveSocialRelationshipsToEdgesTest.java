package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.follow.AccountFollow;
import dev.christopherbell.post.like.PostLike;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith(MockitoExtension.class)
class V009MoveSocialRelationshipsToEdgesTest {
  @Mock private MongoTemplate mongo;
  @Mock private IndexOperations postIndexes;
  @Mock private IndexOperations likeIndexes;
  @Mock private IndexOperations followIndexes;
  @Mock private IndexInfo legacyLikeIndex;

  @Test
  void copiesDistinctLegacyRelationshipsBeforeRemovingArrays() {
    when(mongo.indexOps("posts")).thenReturn(postIndexes);
    when(postIndexes.getIndexInfo()).thenReturn(List.of(legacyLikeIndex));
    when(legacyLikeIndex.getName()).thenReturn("void_people_kept_alive_activity");
    when(mongo.indexOps(PostLike.COLLECTION)).thenReturn(likeIndexes);
    when(mongo.indexOps(AccountFollow.COLLECTION)).thenReturn(followIndexes);
    when(mongo.find(any(Query.class), eq(Document.class), eq("posts")))
        .thenReturn(
            List.of(new Document("_id", "post-1")
                .append("likedBy", List.of("a1", "a1", "a2", ""))),
            List.of());
    when(mongo.find(any(Query.class), eq(Document.class), eq("accounts")))
        .thenReturn(
            List.of(new Document("_id", "a1")
                .append("followingIds", List.of("a2", "a2"))),
            List.of());

    new V009MoveSocialRelationshipsToEdges().apply(mongo);

    verify(postIndexes).dropIndex("void_people_kept_alive_activity");
    verify(mongo, times(2)).upsert(any(Query.class), any(UpdateDefinition.class), eq(PostLike.class));
    verify(mongo).upsert(any(Query.class), any(UpdateDefinition.class), eq(AccountFollow.class));
    var postUnset = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).updateMulti(any(Query.class), postUnset.capture(), eq("posts"));
    assertThat(postUnset.getValue().getUpdateObject().toString()).contains("$unset", "likedBy");
    var accountUnset = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).updateMulti(any(Query.class), accountUnset.capture(), eq("accounts"));
    assertThat(accountUnset.getValue().getUpdateObject().toString())
        .contains("$unset", "followingIds");
  }
}
