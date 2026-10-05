package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoDatabase;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class MongoDatabaseConnectivityProbeTest {
  @Test
  void pingReturnsFalseWhenMongoRejectsTheCommand() {
    var mongo = mock(MongoTemplate.class);
    doThrow(new IllegalStateException("MongoDB is unavailable"))
        .when(mongo).executeCommand(any(Document.class));
    var probe = new MongoDatabaseConnectivityProbe(mongo);

    var pingSucceeded = probe.ping(Duration.ofSeconds(1));

    assertThat(pingSucceeded).isFalse();
    verify(mongo).executeCommand(any(Document.class));
  }

  @Test
  void identityPreservesMongoFailureAsTheTranslationCause() {
    var mongo = mock(MongoTemplate.class);
    var database = mock(MongoDatabase.class);
    var mongoFailure = new IllegalArgumentException("database name lookup failed");
    when(mongo.getDb()).thenReturn(database);
    when(database.getName()).thenThrow(mongoFailure);
    var probe = new MongoDatabaseConnectivityProbe(mongo);

    assertThatThrownBy(() -> probe.identity(Duration.ofSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The MongoDB identity probe failed.")
        .satisfies(failure -> {
          assertThat(failure.getCause()).isInstanceOf(ExecutionException.class);
          assertThat(failure.getCause().getCause()).isSameAs(mongoFailure);
        });
  }

  @Test
  void pingRejectsTimeoutsThatCannotBeConvertedBeforeStartingMongoWork() {
    var mongo = mock(MongoTemplate.class);
    var probe = new MongoDatabaseConnectivityProbe(mongo);

    assertThatThrownBy(() -> probe.ping(Duration.ofSeconds(Long.MAX_VALUE)))
        .isInstanceOf(ArithmeticException.class);

    verifyNoInteractions(mongo);
  }
}
