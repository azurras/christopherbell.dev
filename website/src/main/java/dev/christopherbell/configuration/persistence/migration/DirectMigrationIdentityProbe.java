package dev.christopherbell.configuration.persistence.migration;

import com.mongodb.client.MongoClients;
import java.net.URI;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.bson.Document;

/** Read-only identity probes used before any migration source read or target write. */
public final class DirectMigrationIdentityProbe implements MigrationIdentityProbe {
  private final DataSource target;

  public DirectMigrationIdentityProbe(DataSource target) {
    this.target = target;
  }

  @Override
  public MigrationDatabaseIdentity sourceIdentity(MigrationRequest request) {
    try {
      var endpoint = URI.create(request.sourceUri());
      try (var client = MongoClients.create(request.sourceUri())) {
        client.getDatabase(request.sourceDatabase()).runCommand(new Document("ping", 1));
      }
      return new MigrationDatabaseIdentity(
          endpoint.getHost(), endpoint.getPort(), request.sourceDatabase(), null);
    } catch (RuntimeException failure) {
      throw new MigrationPreflightException(
          "PostgreSQL migration preflight could not verify source identity.");
    }
  }

  @Override
  public MigrationDatabaseIdentity targetIdentity(MigrationRequest request) {
    try (var connection = target.getConnection();
         var statement = connection.createStatement();
         var rows = statement.executeQuery(
             "select current_database(), current_user, host(inet_server_addr()), inet_server_port()")) {
      if (!rows.next()) {
        throw new SQLException("Target identity query returned no row.");
      }
      return new MigrationDatabaseIdentity(
          rows.getString(3), rows.getInt(4), rows.getString(1), rows.getString(2));
    } catch (SQLException failure) {
      throw new MigrationPreflightException(
          "PostgreSQL migration preflight could not verify target identity.");
    }
  }
}
