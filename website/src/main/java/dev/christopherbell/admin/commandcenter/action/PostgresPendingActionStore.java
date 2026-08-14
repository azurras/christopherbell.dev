package dev.christopherbell.admin.commandcenter.action;

import static dev.christopherbell.persistence.jooq.platform.Tables.PENDING_ACTION;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL atomic fixed-key machine power-action reservation. */
@PostgresPersistence
public class PostgresPendingActionStore implements PendingActionStore {
  private static final String ID = "machine-power";
  private final DSLContext database;

  public PostgresPendingActionStore(DSLContext database) {
    this.database = database;
  }

  @Override
  public boolean reserve(Reservation reservation, Instant now) {
    var row = database.insertInto(PENDING_ACTION)
        .set(PENDING_ACTION.PENDING_ACTION_ID, ID)
        .set(PENDING_ACTION.ACTION, reservation.action().name())
        .set(PENDING_ACTION.ACCEPTED_AT, reservation.acceptedAt().atOffset(ZoneOffset.UTC))
        .set(PENDING_ACTION.EXECUTE_AT, reservation.executeAt().atOffset(ZoneOffset.UTC))
        .onConflict(PENDING_ACTION.PENDING_ACTION_ID).doUpdate()
        .set(PENDING_ACTION.ACTION, reservation.action().name())
        .set(PENDING_ACTION.ACCEPTED_AT, reservation.acceptedAt().atOffset(ZoneOffset.UTC))
        .set(PENDING_ACTION.EXECUTE_AT, reservation.executeAt().atOffset(ZoneOffset.UTC))
        .where(PENDING_ACTION.EXECUTE_AT.le(now.atOffset(ZoneOffset.UTC)))
        .returning(PENDING_ACTION.PENDING_ACTION_ID)
        .fetchOne();
    return row != null;
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
    return database.deleteFrom(PENDING_ACTION)
        .where(PENDING_ACTION.PENDING_ACTION_ID.eq(ID)
            .and(PENDING_ACTION.ACTION.eq(reservation.action().name()))
            .and(PENDING_ACTION.ACCEPTED_AT.eq(reservation.acceptedAt().atOffset(ZoneOffset.UTC)))
            .and(PENDING_ACTION.EXECUTE_AT.eq(reservation.executeAt().atOffset(ZoneOffset.UTC))))
        .execute() == 1;
  }

  @Override
  public void reconcile(Instant now) {
    database.deleteFrom(PENDING_ACTION)
        .where(PENDING_ACTION.PENDING_ACTION_ID.eq(ID)
            .and(PENDING_ACTION.EXECUTE_AT.le(now.atOffset(ZoneOffset.UTC))))
        .execute();
  }

  private Optional<Reservation> findReservation() {
    return database.selectFrom(PENDING_ACTION)
        .where(PENDING_ACTION.PENDING_ACTION_ID.eq(ID))
        .fetchOptional(row -> new Reservation(
            CommandCenterActionType.valueOf(row.getAction()),
            row.getAcceptedAt().toInstant(),
            row.getExecuteAt().toInstant()));
  }
}
