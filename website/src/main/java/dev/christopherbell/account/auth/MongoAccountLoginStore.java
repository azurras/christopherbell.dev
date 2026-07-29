package dev.christopherbell.account.auth;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Mongo implementation of the conditional login update boundary. */
@Repository
@RequiredArgsConstructor
class MongoAccountLoginStore implements AccountLoginStore {
  private final MongoTemplate mongo;

  @Override
  public Optional<Account> completeLogin(
      Account observed,
      String passwordHash,
      Instant loginOn
  ) {
    var query = new Query(new Criteria().andOperator(
        Criteria.where("_id").is(observed.getId()),
        Criteria.where("status").is(AccountStatus.ACTIVE),
        Criteria.where("passwordHash").is(observed.getPasswordHash()),
        Criteria.where("passwordSalt").is(observed.getPasswordSalt())));
    var update = new Update()
        .set("lastLoginOn", loginOn)
        .set("passwordHash", passwordHash)
        .unset("passwordSalt");
    return Optional.ofNullable(mongo.findAndModify(
        query,
        update,
        FindAndModifyOptions.options().returnNew(true),
        Account.class));
  }
}
