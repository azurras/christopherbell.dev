package dev.christopherbell.configuration.mongo.domain;

import dev.christopherbell.configuration.persistence.MongoBackendComponent;
import java.util.Objects;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mapping.callback.EntityCallbacks;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Creates type-bound operations from the cutover manifest or explicit additive runtime approvals. */
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
    if (javaType.getName().equals(SURVIVE_WORLD_TYPE)) {
      return new MongoKindScopedOperations<>(mongo, SURVIVE_KINDS.require(
          "survive_world", 1, javaType), callbacks);
    }
    if (javaType.getName().equals(MONITOR_WORKSPACE_TYPE)) {
      return new MongoKindScopedOperations<>(mongo, MONITOR_KINDS.require(
          "site_monitor_workspace", 1, javaType), callbacks);
    }
    if (javaType.getName().equals(MONITOR_SCHEDULE_TYPE)) {
      return new MongoKindScopedOperations<>(mongo, MONITOR_KINDS.require(
          "site_monitor_schedule", 1, javaType), callbacks);
    }
    return new MongoKindScopedOperations<>(
        mongo, DomainCollectionManifest.forType(javaType), callbacks);
  }

  // Additive runtime approval is deliberately separate from the immutable cutover manifest.
  private static final String SURVIVE_WORLD_TYPE = "dev.christopherbell.survive.model.SurviveSavedWorld";
  private static final DomainDocumentKindRegistry SURVIVE_KINDS = DomainDocumentKindRegistry.of(
      java.util.Map.of("survive_world", "application_runtime"));
  private static final String MONITOR_WORKSPACE_TYPE =
      "dev.christopherbell.sitemonitor.model.MonitorWorkspace";
  private static final String MONITOR_SCHEDULE_TYPE =
      "dev.christopherbell.sitemonitor.model.MonitorSchedule";
  private static final DomainDocumentKindRegistry MONITOR_KINDS = DomainDocumentKindRegistry.of(
      java.util.Map.of("site_monitor_workspace", "application_runtime",
          "site_monitor_schedule", "application_runtime"));

  KindScopedMongoOperations<?> forExactKind(String kind) {
    if ("site_monitor_workspace".equals(kind) || "survive_world".equals(kind)) {
      try {
        return forUnknownType(Class.forName("survive_world".equals(kind) ? SURVIVE_WORLD_TYPE : MONITOR_WORKSPACE_TYPE));
      } catch (ClassNotFoundException failure) {
        throw new IllegalStateException("Additive runtime owner type is unavailable.", failure);
      }
    }
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
