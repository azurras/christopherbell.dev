package dev.christopherbell.admin.commandcenter.action;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Mongo implementation of the fixed-key pending machine power-action boundary. */
@Repository
class MongoPendingActionStore implements PendingActionStore {
  private final KindScopedMongoOperations<PendingActionDocument> mongo;

  MongoPendingActionStore(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(PendingActionDocument.class);
  }

  @Override
  public boolean reserve(Reservation reservation, Instant now) {
    var query = Query.query(Criteria.where("id")
        .is(PendingActionDocument.ID)
        .orOperator(
            Criteria.where("executeAt").lte(now),
            Criteria.where("executeAt").exists(false)));
    var update = new Update()
        .set("action", reservation.action())
        .set("acceptedAt", reservation.acceptedAt())
        .set("executeAt", reservation.executeAt());
    try {
      var pending = new PendingActionDocument();
      pending.setId(PendingActionDocument.ID);
      pending.setAction(reservation.action());
      pending.setAcceptedAt(reservation.acceptedAt());
      pending.setExecuteAt(reservation.executeAt());
      mongo.insert(pending);
      return true;
    } catch (DuplicateKeyException contention) {
      return mongo.findAndUpdate(query, update).isPresent();
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
    var query = Query.query(Criteria.where("id")
        .is(PendingActionDocument.ID)
        .and("action").is(reservation.action())
        .and("acceptedAt").is(reservation.acceptedAt())
        .and("executeAt").is(reservation.executeAt()));
    return mongo.remove(query).getDeletedCount() == 1;
  }

  @Override
  public void reconcile(Instant now) {
    var query = Query.query(Criteria.where("id")
        .is(PendingActionDocument.ID)
        .and("executeAt").lte(now));
    mongo.remove(query);
  }

  private Optional<Reservation> findReservation() {
    return mongo.findById(PendingActionDocument.ID)
        .map(document -> new Reservation(
            document.getAction(), document.getAcceptedAt(), document.getExecuteAt()));
  }
}
