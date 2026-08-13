package dev.christopherbell.message;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;

/** Test-only package bridge that preserves the production adapter's package-private visibility. */
public final class MongoMessageRepositoryTestFactory {
  private MongoMessageRepositoryTestFactory() {}

  public static MessageRepository create(DomainMongoOperationsFactory factory) {
    return new MongoMessageRepository(factory);
  }
}
