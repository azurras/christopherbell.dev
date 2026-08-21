package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import org.jooq.DSLContext;

/** Bounded PostgreSQL connectivity probe selected for the PostgreSQL backend. */
@PostgresPersistence
public class PostgresDatabaseConnectivityProbe
    implements DatabaseConnectivityProbe, PersistenceIdentityProbe {
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

  @Override
  public PersistenceIdentity identity(Duration timeout) {
    var task = new FutureTask<>(() -> database.fetchOne(
        "select current_database(), version::text "
            + "from public.flyway_schema_history where success "
            + "order by installed_rank desc limit 1"));
    Thread.ofVirtual().name("command-center-postgresql-identity").start(task);
    try {
      var record = task.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      if (record == null || record.get(0) == null || record.get(1) == null) {
        throw new IllegalStateException("The PostgreSQL identity is incomplete.");
      }
      return new PersistenceIdentity(
          "postgresql", record.get(0).toString(), record.get(1).toString());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      task.cancel(true);
      throw new IllegalStateException("The PostgreSQL identity probe was interrupted.");
    } catch (Exception failure) {
      task.cancel(true);
      throw new IllegalStateException("The PostgreSQL identity probe failed.");
    }
  }
}
