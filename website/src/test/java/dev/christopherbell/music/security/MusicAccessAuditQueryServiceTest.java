package dev.christopherbell.music.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.Query;

class MusicAccessAuditQueryServiceTest {
  @Test
  @SuppressWarnings("unchecked")
  void recentAttemptsAreNewestFirstAndHardCapped() {
    var factory = mock(DomainMongoOperationsFactory.class);
    var attempts = (KindScopedMongoOperations<MusicAccessAttempt>) mock(KindScopedMongoOperations.class);
    when(factory.forType(MusicAccessAttempt.class)).thenReturn(attempts);
    var query = ArgumentCaptor.forClass(Query.class);
    when(attempts.find(query.capture(), any(Pageable.class))).thenReturn(List.of());

    assertThat(new MusicAccessAuditQueryService(factory).recent(Integer.MAX_VALUE)).isEmpty();

    assertThat(query.getValue().getLimit()).isEqualTo(100);
    assertThat(query.getValue().getSortObject().get("lastAttemptAt")).isEqualTo(-1);
  }
}
