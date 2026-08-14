package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import org.jooq.DSLContext;

/** Bounded PostgreSQL connectivity probe selected for the PostgreSQL backend. */
@PostgresPersistence
public class PostgresDatabaseConnectivityProbe implements DatabaseConnectivityProbe {
  private final DSLContext database;

  public PostgresDatabaseConnectivityProbe(DSLContext database) {
    this.database = database;
  }

  @Override
  public String backendName() {
    return "postgresql";
  }

  @Override
  public boolean ping(Duration timeout) {
    var task = new FutureTask<>(() -> database.fetchExists(database.selectOne()));
    Thread.ofVirtual().name("command-center-postgresql-ping").start(task);
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
}
