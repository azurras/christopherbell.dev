package dev.christopherbell.configuration.mongo.migration;

import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Creates bounded scan, uniqueness, due-job, and abandoned-claim indexes. */
@Component
public final class V007EnsureFederationOutboundIndexes implements ApplicationMigration {
  private static final String CHECKSUM =
      "1f9d1ddf7fcdb35e66556310b541bedbed4444c2178ebdf0dc22455c22b9ec82";

  @Override
  public String id() {
    return "007-ensure-federation-outbound-indexes";
  }

  @Override
  public String checksum() {
    return CHECKSUM;
  }

  @Override
  public String description() {
    return "Ensure controlled federation outbound delivery indexes";
  }

  @Override
  public void apply(MongoTemplate mongo) {
    mongo.indexOps("posts").createIndex(new Index()
        .on("federationOutboundEligible", Direction.ASC)
        .on("createdOn", Direction.ASC)
        .on("_id", Direction.ASC)
        .named("federation_outbound_post_scan"));
    mongo.indexOps("federation_delivery_jobs").createIndex(new Index()
        .on("postId", Direction.ASC)
        .on("peerName", Direction.ASC)
        .unique()
        .named("federation_delivery_post_peer_unique"));
    mongo.indexOps("federation_delivery_jobs").createIndex(new Index()
        .on("state", Direction.ASC)
        .on("nextAttemptOn", Direction.ASC)
        .on("createdOn", Direction.ASC)
        .named("federation_delivery_due"));
    mongo.indexOps("federation_delivery_jobs").createIndex(new Index()
        .on("state", Direction.ASC)
        .on("claimUntil", Direction.ASC)
        .named("federation_delivery_expired_claim"));
  }
}
