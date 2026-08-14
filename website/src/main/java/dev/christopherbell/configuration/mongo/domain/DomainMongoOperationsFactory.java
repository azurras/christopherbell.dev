package dev.christopherbell.configuration.mongo.domain;

import dev.christopherbell.configuration.persistence.MongoBackendComponent;
import java.util.Objects;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mapping.callback.EntityCallbacks;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Creates type-bound operations whose physical collection and kind come only from the manifest. */
@MongoBackendComponent
public final class DomainMongoOperationsFactory {
  private final MongoTemplate mongo;
  private final EntityCallbacks callbacks;

  DomainMongoOperationsFactory(MongoTemplate mongo) {
    this(mongo, EntityCallbacks.create());
  }

  @Autowired
  public DomainMongoOperationsFactory(MongoTemplate mongo, BeanFactory beanFactory) {
    this(mongo, EntityCallbacks.create(beanFactory));
  }

  private DomainMongoOperationsFactory(MongoTemplate mongo, EntityCallbacks callbacks) {
    this.mongo = Objects.requireNonNull(mongo, "mongo");
    this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
  }

  /** Returns a new stateless operations boundary for one exact approved domain type. */
  public <T> KindScopedMongoOperations<T> forType(Class<T> javaType) {
    return new MongoKindScopedOperations<>(
        mongo, DomainCollectionManifest.forType(javaType), callbacks);
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
