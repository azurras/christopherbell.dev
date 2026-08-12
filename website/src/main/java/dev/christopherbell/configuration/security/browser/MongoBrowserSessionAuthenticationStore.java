package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.mongo.domain.KindScopedAggregation;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

/** Mongo join that resolves a browser session and current account in one command. */
@Repository
public class MongoBrowserSessionAuthenticationStore implements BrowserSessionAuthenticationStore {
  private static final String ACCOUNT_COLLECTION = "accounts";

  private final KindScopedMongoOperations<BrowserSession> sessions;

  public MongoBrowserSessionAuthenticationStore(DomainMongoOperationsFactory factory) {
    this.sessions = factory.forType(BrowserSession.class);
  }

  @Override
  public Optional<BrowserSessionAuthentication> findById(String sessionId) {
    var aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("id").is(sessionId)),
        Aggregation.limit(1),
        context -> new Document("$lookup", new Document("from", ACCOUNT_COLLECTION)
            .append("let", new Document("accountId", "$accountId"))
            .append("pipeline", List.of(
                new Document("$match", new Document("$expr", new Document(
                    "$and", List.of(
                        new Document("$eq", List.of("$_kind", "account")),
                        new Document("$eq", List.of("$_id.legacyId", "$$accountId")))))),
                new Document("$replaceRoot", new Document("newRoot", new Document(
                    "$mergeObjects", List.of("$payload", new Document("_id", "$_id.legacyId"))))),
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
    return sessions.aggregate(
        KindScopedAggregation.withForeignKinds(
            aggregation, KindScopedAggregation.ForeignKind.ACCOUNT),
        BrowserSessionAuthentication.class).stream().findFirst();
  }
}
