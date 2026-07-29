package dev.christopherbell.configuration.security.browser;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

/** Mongo join that resolves a browser session and current account in one command. */
@Repository
@RequiredArgsConstructor
public class MongoBrowserSessionAuthenticationStore implements BrowserSessionAuthenticationStore {
  private static final String SESSION_COLLECTION = "browser_sessions";
  private static final String ACCOUNT_COLLECTION = "accounts";

  private final MongoTemplate mongo;

  @Override
  public Optional<BrowserSessionAuthentication> findById(String sessionId) {
    var aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("_id").is(sessionId)),
        Aggregation.limit(1),
        context -> new Document("$lookup", new Document("from", ACCOUNT_COLLECTION)
            .append("let", new Document("accountId", "$accountId"))
            .append("pipeline", List.of(
                new Document("$match", new Document("$expr", new Document(
                    "$eq", List.of("$_id", "$$accountId")))),
                new Document("$project", new Document("_id", 1)
                    .append("passwordHash", 1)
                    .append("role", 1)
                    .append("permissions", 1)
                    .append("status", 1))))
            .append("as", "currentAccount")),
        Aggregation.unwind("currentAccount"),
        context -> new Document("$project", new Document("_id", 0)
            .append("session", "$$ROOT")
            .append("account", "$currentAccount")));
    return Optional.ofNullable(mongo.aggregate(
        aggregation, SESSION_COLLECTION, BrowserSessionAuthentication.class)
        .getUniqueMappedResult());
  }
}
