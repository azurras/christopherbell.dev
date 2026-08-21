package dev.christopherbell.notification;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;

/** Test-only package bridge that preserves the production adapter's package-private visibility. */
public final class MongoNotificationRepositoryTestFactory {
  private MongoNotificationRepositoryTestFactory() {}

  public static NotificationRepository create(DomainMongoOperationsFactory factory) {
    return new MongoNotificationRepository(factory);
  }
}
