package dev.christopherbell.configuration.mongo.migration;

import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Creates the compound indexes used by bounded public Void discovery queries. */
@Component
public final class V004EnsureVoidDiscoveryIndexes implements ApplicationMigration {
  private static final String CHECKSUM =
      "fe6a635eee77ba364de13ac5bfe02865cd07689ff5991c11a5d8dc6229fd19e1";

  @Override
  public String id() {
    return "004-ensure-void-discovery-indexes";
  }

  @Override
  public String checksum() {
    return CHECKSUM;
  }

  @Override
  public String description() {
    return "Ensure bounded Void public discovery indexes";
  }

  @Override
  public void apply(MongoTemplate mongo) {
    var indexes = mongo.indexOps("posts");
    indexes.createIndex(new Index()
        .on("parentId", Direction.ASC)
        .on("createdOn", Direction.DESC)
        .on("_id", Direction.DESC)
        .on("expiresOn", Direction.ASC)
        .named("void_discovery_new"));
    indexes.createIndex(new Index()
        .on("parentId", Direction.ASC)
        .on("expiresOn", Direction.ASC)
        .on("_id", Direction.ASC)
        .named("void_discovery_fading"));
    indexes.createIndex(new Index()
        .on("parentId", Direction.ASC)
        .on("lastExtendedOn", Direction.DESC)
        .on("_id", Direction.DESC)
        .on("expiresOn", Direction.ASC)
        .named("void_discovery_revived"));
    indexes.createIndex(new Index()
        .on("topics.canonical", Direction.ASC)
        .on("expiresOn", Direction.ASC)
        .on("rootId", Direction.ASC)
        .named("void_discovery_topic"));
  }
}
