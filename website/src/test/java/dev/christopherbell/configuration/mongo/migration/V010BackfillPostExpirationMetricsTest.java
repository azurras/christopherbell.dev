package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith(MockitoExtension.class)
class V010BackfillPostExpirationMetricsTest {
  @Mock private MongoTemplate mongo;

  @Test
  void derivesRootCountersAndSynchronizesRepliesWithoutLoadingTheThread() {
    var createdOn = Instant.parse("2026-07-29T00:00:00Z");
    var root = new Document("_id", "root")
        .append("createdOn", Date.from(createdOn))
        .append("likesCount", 1)
        .append("threadReplyLikesCount", 2);
    when(mongo.find(any(Query.class), eq(Document.class), eq("posts")))
        .thenReturn(List.of(root), List.of());
    when(mongo.count(any(Query.class), eq("posts"))).thenReturn(3L);

    new V010BackfillPostExpirationMetrics().apply(mongo);

    var rootUpdate = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).updateFirst(any(Query.class), rootUpdate.capture(), eq("posts"));
    assertThat(rootUpdate.getValue().getUpdateObject().toString())
        .contains("threadReplyCount", "3", createdOn.plusSeconds(7L * 86_400L).toString());
    var replyUpdate = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).updateMulti(any(Query.class), replyUpdate.capture(), eq("posts"));
    assertThat(replyUpdate.getValue().getUpdateObject().toString()).contains("expiresOn");
  }
}
