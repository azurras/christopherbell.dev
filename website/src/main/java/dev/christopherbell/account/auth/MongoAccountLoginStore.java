package dev.christopherbell.account.auth;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Mongo implementation of the conditional login update boundary. */
@MongoPersistence
@Repository
class MongoAccountLoginStore implements AccountLoginStore {
  private final KindScopedMongoOperations<Account> mongo;

  MongoAccountLoginStore(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(Account.class);
  }

  @Override
  public Optional<Account> completeLogin(
      Account observed,
      String passwordHash,
      Instant loginOn
  ) {
    var query = new Query(new Criteria().andOperator(
        Criteria.where("id").is(observed.getId()),
        Criteria.where("status").is(AccountStatus.ACTIVE),
        Criteria.where("passwordHash").is(observed.getPasswordHash()),
        Criteria.where("passwordSalt").is(observed.getPasswordSalt())));
    var update = new Update()
        .set("lastLoginOn", loginOn)
        .set("passwordHash", passwordHash)
        .unset("passwordSalt");
    return mongo.findAndUpdate(query, update);
  }
}
