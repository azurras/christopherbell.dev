package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Blocks a target-schema release until the exact domain cutover is active. */
@MongoPersistence
@Component
public final class V015RequireDomainCollectionSchema implements ApplicationMigration {
  private final DomainCollectionCutoverLedger ledger;

  public V015RequireDomainCollectionSchema(DomainCollectionCutoverLedger ledger) {
    this.ledger = ledger;
  }

  @Override
  public String id() {
    return "015-require-domain-collection-schema";
  }

  @Override
  public String checksum() {
    return DomainCollectionManifest.DIGEST;
  }

  @Override
  public String description() {
    return "Require the published 14-collection schema";
  }

  @Override
  public void apply(MongoTemplate mongo) {
    ledger.requireTargetActive();
  }
}
