package dev.christopherbell.admin.commandcenter.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.mongodb.MongoException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class MongoDatabaseConnectivityProbeTest {
  @Test
  void pingReturnsUnavailableWhenMongoRejectsTheRequest() {
    var mongo = mock(MongoTemplate.class);
    doThrow(new MongoException("database is unavailable"))
        .when(mongo).executeCommand(any(Document.class));
    var probe = new MongoDatabaseConnectivityProbe(mongo);

    assertFalse(probe.ping(Duration.ofSeconds(2)));
  }

  @Test
  void identityFailurePreservesTheMongoCause() {
    var mongo = mock(MongoTemplate.class);
    var mongoFailure = new MongoException("database is unavailable");
    doThrow(mongoFailure).when(mongo).getDb();
    var probe = new MongoDatabaseConnectivityProbe(mongo);

    var failure = assertThrows(
        IllegalStateException.class,
        () -> probe.identity(Duration.ofSeconds(2)));

    assertEquals("The MongoDB identity probe failed.", failure.getMessage());
    var taskFailure = assertInstanceOf(ExecutionException.class, failure.getCause());
    assertEquals(mongoFailure, taskFailure.getCause());
  }
}
