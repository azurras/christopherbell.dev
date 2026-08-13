package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Removes the retired approval fields after account status became authoritative. */
@MongoPersistence
@Component
public final class V008RemoveAccountApprovalFields implements ApplicationMigration {
  private static final String CHECKSUM =
      "498c9c6fd6622cc1734199544cf888a14cf5e72015a1c71cda71db229077fd28";

  @Override
  public String id() {
    return "008-remove-account-approval-fields";
  }

  @Override
  public String checksum() {
    return CHECKSUM;
  }

  @Override
  public String description() {
    return "Remove retired account approval fields";
  }

  @Override
  public void apply(MongoTemplate mongo) {
    mongo.updateMulti(
        new Query(),
        new Update().unset("isApproved").unset("approvedBy"),
        "accounts");
  }
}
