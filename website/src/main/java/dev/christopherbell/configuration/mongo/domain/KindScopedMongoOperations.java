package dev.christopherbell.configuration.mongo.domain;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Domain-shaped Mongo operations that cannot escape one approved logical kind. */
public interface KindScopedMongoOperations<T> {
  Optional<T> findById(Object legacyId);

  Optional<T> findOne(Query domainQuery);

  List<T> find(Query domainQuery, Pageable page);

  long count(Query domainQuery);

  boolean exists(Query domainQuery);

  T insert(T value);

  T save(T value);

  UpdateResult updateFirst(Query domainQuery, Update domainUpdate);

  /** Applies an owner/state heartbeat without advancing an entity's optimistic version. */
  UpdateResult updateHeartbeatPreservingVersion(
      Query exactOwnerStateQuery, Update heartbeatUpdate);

  Optional<T> findAndUpdate(Query domainQuery, Update domainUpdate);

  T upsertById(Object legacyId, Update domainUpdate);

  Optional<T> decrementFloorZeroById(
      Object legacyId,
      String counterField,
      int decrement,
      String timestampField,
      Instant changedOn);

  UpdateResult updateMulti(Query domainQuery, Update domainUpdate);

  <R> List<R> aggregate(KindScopedAggregation domainAggregation, Class<R> resultType);

  DeleteResult remove(Query domainQuery);

  String collectionName();
}
