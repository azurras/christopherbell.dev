package dev.christopherbell.music.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class MusicAccessAuditQueryServiceTest {

  @Test
  void recentAttemptsAreNewestFirstAndHardCapped() {
    var mongo = mock(MongoTemplate.class);
    var query = ArgumentCaptor.forClass(Query.class);
    when(mongo.find(query.capture(), eq(MusicAccessAttempt.class))).thenReturn(List.of());

    var result = new MusicAccessAuditQueryService(mongo).recent(Integer.MAX_VALUE);

    assertThat(result).isEmpty();
    assertThat(query.getValue().getLimit()).isEqualTo(100);
    assertThat(query.getValue().getSortObject().get("lastAttemptAt"))
        .isEqualTo(-1);
  }
}
