package dev.christopherbell.admin.commandcenter.action;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL atomic fixed-key machine power-action reservation. */
@PostgresPersistence
public class PostgresPendingActionStore implements PendingActionStore {
  private static final String ID = "machine-power";
  private final JdbcClient database;
  private final String table;

  public PostgresPendingActionStore(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("platform", "pending_action");
  }

  @Override
  public boolean reserve(Reservation reservation, Instant now) {
    return database.sql("""
            insert into %s (pending_action_id, action, accepted_at, execute_at)
            values (:id, :action, :acceptedAt, :executeAt)
            on conflict (pending_action_id) do update set
              action = excluded.action,
              accepted_at = excluded.accepted_at,
              execute_at = excluded.execute_at
            where %s.execute_at <= :now
            returning pending_action_id
            """.formatted(table, table))
        .param("id", ID).param("action", reservation.action().name())
        .param("acceptedAt", reservation.acceptedAt().atOffset(ZoneOffset.UTC))
        .param("executeAt", reservation.executeAt().atOffset(ZoneOffset.UTC))
        .param("now", now.atOffset(ZoneOffset.UTC)).query(String.class).optional().isPresent();
  }

  @Override
  public Optional<Reservation> active(Instant now) {
    var current = findReservation();
    if (current.isEmpty() || now.isBefore(current.get().executeAt())) {
      return current;
    }
    if (clear(current.get())) {
      return Optional.empty();
    }
    return findReservation().filter(reservation -> now.isBefore(reservation.executeAt()));
  }

  @Override
  public boolean clear(Reservation reservation) {
    return database.sql("""
            delete from %s where pending_action_id = :id and action = :action
              and accepted_at = :acceptedAt and execute_at = :executeAt
            """.formatted(table))
        .param("id", ID).param("action", reservation.action().name())
        .param("acceptedAt", reservation.acceptedAt().atOffset(ZoneOffset.UTC))
        .param("executeAt", reservation.executeAt().atOffset(ZoneOffset.UTC)).update() == 1;
  }

  @Override
  public void reconcile(Instant now) {
    database.sql("delete from %s where pending_action_id = :id and execute_at <= :now"
            .formatted(table))
        .param("id", ID).param("now", now.atOffset(ZoneOffset.UTC)).update();
  }

  private Optional<Reservation> findReservation() {
    return database.sql("select * from %s where pending_action_id = :id".formatted(table))
        .param("id", ID).query(PostgresPendingActionStore::map).optional();
  }

  private static Reservation map(java.sql.ResultSet row, int rowNumber) throws SQLException {
    return new Reservation(
        CommandCenterActionType.valueOf(row.getString("action")),
        row.getObject("accepted_at", OffsetDateTime.class).toInstant(),
        row.getObject("execute_at", OffsetDateTime.class).toInstant());
  }
}
