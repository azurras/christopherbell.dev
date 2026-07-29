package dev.christopherbell.admin.commandcenter.action;

import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Mongo implementation of the fixed-key pending machine power-action boundary. */
@Repository
class MongoPendingActionStore implements PendingActionStore {
  private final MongoTemplate mongo;

  MongoPendingActionStore(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @Override
  public boolean reserve(Reservation reservation, Instant now) {
    var query = Query.query(Criteria.where("_id")
        .is(PendingActionDocument.ID)
        .orOperator(
            Criteria.where("executeAt").lte(now),
            Criteria.where("executeAt").exists(false)));
    var update = new Update()
        .set("action", reservation.action())
        .set("acceptedAt", reservation.acceptedAt())
        .set("executeAt", reservation.executeAt());
    try {
      return mongo.findAndModify(
          query,
          update,
          FindAndModifyOptions.options().upsert(true).returnNew(true),
          PendingActionDocument.class) != null;
    } catch (DuplicateKeyException contention) {
      return false;
    }
  }

  @Override
  public Optional<Reservation> active(Instant now) {
    var reservation = findReservation();
    if (reservation.isEmpty() || now.isBefore(reservation.get().executeAt())) {
      return reservation;
    }
    if (clear(reservation.get())) {
      return Optional.empty();
    }
    return findReservation().filter(current -> now.isBefore(current.executeAt()));
  }

  @Override
  public boolean clear(Reservation reservation) {
    var query = Query.query(Criteria.where("_id")
        .is(PendingActionDocument.ID)
        .and("action").is(reservation.action())
        .and("acceptedAt").is(reservation.acceptedAt())
        .and("executeAt").is(reservation.executeAt()));
    return mongo.remove(query, PendingActionDocument.class).getDeletedCount() == 1;
  }

  @Override
  public void reconcile(Instant now) {
    var query = Query.query(Criteria.where("_id")
        .is(PendingActionDocument.ID)
        .and("executeAt").lte(now));
    mongo.remove(query, PendingActionDocument.class);
  }

  private Optional<Reservation> findReservation() {
    return Optional.ofNullable(
        mongo.findById(PendingActionDocument.ID, PendingActionDocument.class))
        .map(document -> new Reservation(
            document.getAction(), document.getAcceptedAt(), document.getExecuteAt()));
  }
}
