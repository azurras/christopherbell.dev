package dev.christopherbell.configuration.mongo.domain;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
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

  DeleteResult remove(Query domainQuery);

  String collectionName();
}
