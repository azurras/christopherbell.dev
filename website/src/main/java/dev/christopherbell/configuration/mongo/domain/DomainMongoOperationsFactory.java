package dev.christopherbell.configuration.mongo.domain;

import java.util.Objects;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Creates type-bound operations whose physical collection and kind come only from the manifest. */
@Component
public final class DomainMongoOperationsFactory {
  private final MongoTemplate mongo;

  public DomainMongoOperationsFactory(MongoTemplate mongo) {
    this.mongo = Objects.requireNonNull(mongo, "mongo");
  }

  /** Returns a new stateless operations boundary for one exact approved domain type. */
  public <T> KindScopedMongoOperations<T> forType(Class<T> javaType) {
    return new MongoKindScopedOperations<>(mongo, DomainCollectionManifest.forType(javaType));
  }

  KindScopedMongoOperations<?> forExactKind(String kind) {
    var definition = DomainCollectionManifest.forKind(kind)
        .orElseThrow(() -> new IllegalArgumentException("Mongo domain kind is not approved."));
    try {
      return forUnknownType(Class.forName(definition.ownerTypeName()));
    } catch (ClassNotFoundException failure) {
      throw new IllegalStateException("Mongo domain owner type is unavailable.", failure);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private KindScopedMongoOperations<?> forUnknownType(Class<?> javaType) {
    return forType((Class) javaType);
  }
}
