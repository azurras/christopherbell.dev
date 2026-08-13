package dev.christopherbell.post;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;

/** Test-only package bridge that preserves the production adapter's package-private visibility. */
public final class MongoPostRepositoryTestFactory {
  private MongoPostRepositoryTestFactory() {}

  public static PostRepository create(DomainMongoOperationsFactory factory) {
    return new MongoPostRepository(factory);
  }
}
