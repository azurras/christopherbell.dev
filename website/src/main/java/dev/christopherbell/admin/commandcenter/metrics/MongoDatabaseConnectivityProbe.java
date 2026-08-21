package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.configuration.persistence.MongoBackendComponent;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Bounded MongoDB connectivity probe selected only for the transition backend. */
@MongoBackendComponent
@MongoPersistence
public class MongoDatabaseConnectivityProbe
    implements DatabaseConnectivityProbe, PersistenceIdentityProbe {
  private final MongoTemplate mongo;

  public MongoDatabaseConnectivityProbe(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @Override
  public String backendName() {
    return "mongodb";
  }

  @Override
  public boolean ping(Duration timeout) {
    var task = new FutureTask<>(() -> {
      mongo.executeCommand(new Document("ping", 1));
      return true;
    });
    Thread.ofVirtual().name("command-center-mongodb-ping").start(task);
    try {
      return task.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      task.cancel(true);
      return false;
    } catch (Exception failure) {
      task.cancel(true);
      return false;
    }
  }

  @Override
  public PersistenceIdentity identity(Duration timeout) {
    var task = new FutureTask<>(() -> new PersistenceIdentity(
        "mongodb", mongo.getDb().getName(), "legacy"));
    Thread.ofVirtual().name("command-center-mongodb-identity").start(task);
    try {
      return task.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      task.cancel(true);
      throw new IllegalStateException("The MongoDB identity probe was interrupted.");
    } catch (Exception failure) {
      task.cancel(true);
      throw new IllegalStateException("The MongoDB identity probe failed.");
    }
  }
}
