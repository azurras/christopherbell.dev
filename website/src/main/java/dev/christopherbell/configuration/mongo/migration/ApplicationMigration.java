package dev.christopherbell.configuration.mongo.migration;

import org.springframework.data.mongodb.core.MongoTemplate;

/** A versioned, immutable MongoDB migration. */
public interface ApplicationMigration {
  String id();

  String checksum();

  String description();

  void apply(MongoTemplate mongo);
}
