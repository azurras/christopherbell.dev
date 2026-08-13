package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Creates the post and trust indexes used by bounded people discovery. */
@MongoPersistence
@Component
public final class V005EnsureVoidPeopleDiscoveryIndexes implements ApplicationMigration {
  private static final String CHECKSUM =
      "e9f91fdcd36d40e9090fdc1a7e199f0a9a61a35f334c1fd37a9774439da31af4";

  @Override
  public String id() {
    return "005-ensure-void-people-discovery-indexes";
  }

  @Override
  public String checksum() {
    return CHECKSUM;
  }

  @Override
  public String description() {
    return "Ensure bounded Void people discovery indexes";
  }

  @Override
  public void apply(MongoTemplate mongo) {
    var posts = mongo.indexOps("posts");
    posts.createIndex(new Index()
        .on("expiresOn", Direction.ASC)
        .on("accountId", Direction.ASC)
        .named("void_people_active_pool"));
    posts.createIndex(new Index()
        .on("accountId", Direction.ASC)
        .on("expiresOn", Direction.ASC)
        .on("createdOn", Direction.DESC)
        .on("_id", Direction.DESC)
        .named("void_people_authored_activity"));
    posts.createIndex(new Index()
        .on("likedBy", Direction.ASC)
        .on("expiresOn", Direction.ASC)
        .on("createdOn", Direction.DESC)
        .on("_id", Direction.DESC)
        .named("void_people_kept_alive_activity"));
    mongo.indexOps("account_trust_relationships").createIndex(new Index()
        .on("targetAccountId", Direction.ASC)
        .on("type", Direction.ASC)
        .on("ownerAccountId", Direction.ASC)
        .named("void_people_incoming_block"));
  }
}
