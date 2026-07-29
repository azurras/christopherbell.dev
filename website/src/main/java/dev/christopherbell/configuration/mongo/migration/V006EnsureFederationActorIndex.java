package dev.christopherbell.configuration.mongo.migration;

import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Creates the bounded account lookup used by local ActivityPub actor discovery. */
@Component
public final class V006EnsureFederationActorIndex implements ApplicationMigration {
  private static final String CHECKSUM =
      "899abb93df60f83b06d2080a6ab890e1521a94b1617dde0b0502d5eb9c93c555";

  @Override
  public String id() {
    return "006-ensure-federation-actor-index";
  }

  @Override
  public String checksum() {
    return CHECKSUM;
  }

  @Override
  public String description() {
    return "Ensure active consented federation actor lookup index";
  }

  @Override
  public void apply(MongoTemplate mongo) {
    mongo.indexOps("accounts").createIndex(new Index()
        .on("status", Direction.ASC)
        .on("federationEnabled", Direction.ASC)
        .on("username", Direction.ASC)
        .named("federation_actor_lookup"));
  }
}
