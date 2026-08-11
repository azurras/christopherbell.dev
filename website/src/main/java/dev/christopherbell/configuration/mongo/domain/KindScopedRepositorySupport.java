package dev.christopherbell.configuration.mongo.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Shared mechanics for explicit repository ports backed by one fixed domain kind. */
public abstract class KindScopedRepositorySupport<T> {
  protected final KindScopedMongoOperations<T> mongo;

  protected KindScopedRepositorySupport(
      DomainMongoOperationsFactory factory, Class<T> javaType) {
    this.mongo = factory.forType(javaType);
  }

  protected final T saveValue(T value) {
    return mongo.save(value);
  }

  protected final T insertValue(T value) {
    return mongo.insert(value);
  }

  protected final Optional<T> findValueById(Object id) {
    return mongo.findById(id);
  }

  protected final Optional<T> findOne(Query query) {
    return mongo.findOne(query);
  }

  protected final List<T> find(Query query, Pageable page) {
    return mongo.find(query, page);
  }

  protected final List<T> find(Query query) {
    return mongo.find(query, Pageable.unpaged());
  }

  protected final Page<T> page(Query query, Pageable page) {
    return new PageImpl<>(mongo.find(query, page), page, mongo.count(query));
  }

  protected final void deleteById(Object id) {
    mongo.remove(Query.query(Criteria.where("id").is(id)));
  }

  protected final void deleteAllValues(Iterable<T> values, java.util.function.Function<T, ?> id) {
    var ids = new ArrayList<>();
    values.forEach(value -> ids.add(id.apply(value)));
    if (!ids.isEmpty()) {
      mongo.remove(Query.query(Criteria.where("id").in(ids)));
    }
  }
}
