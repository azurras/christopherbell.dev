package dev.christopherbell.configuration.security.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class MongoBrowserSessionActivityStoreTest {
  private static final Instant OBSERVED = Instant.parse("2026-07-29T12:00:00Z");
  private static final Instant NOW = OBSERVED.plusSeconds(300);

  @Test
  void repositoryCanBeProxiedUsingTheApplicationClassProxyMode() {
    try (var context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of("app.persistence.backend=mongodb").applyTo(context);
      var factory = mock(
          dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory.class);
      context.registerBean(PersistenceExceptionTranslationPostProcessor.class, () -> {
        var postProcessor = new PersistenceExceptionTranslationPostProcessor();
        postProcessor.setProxyTargetClass(true);
        return postProcessor;
      });
      context.registerBean(
          MongoBrowserSessionActivityStore.class,
          () -> new MongoBrowserSessionActivityStore(factory));

      context.refresh();

      assertThat(context.getBean(BrowserSessionActivityStore.class)).isNotNull();
    }
  }

  @Test
  void touchUpdatesOnlyActivityFieldsWhenTheObservedLiveSessionStillMatches() {
    var mongo = mock(MongoTemplate.class);
    var expected = BrowserSession.builder().id("session-1").build();
    var store = new MongoBrowserSessionActivityStore(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo));
    var expectedEnvelope =
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
            .envelope(mongo, expected);
    when(mongo.findAndModify(
        any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(Document.class), eq("sessions")))
        .thenReturn(expectedEnvelope);
    Instant idleExpiresOn = NOW.plusSeconds(600);

    var result = store.touch("session-1", OBSERVED, NOW, idleExpiresOn);

    assertThat(result).contains(expected);
    var query = ArgumentCaptor.forClass(Query.class);
    var update = ArgumentCaptor.forClass(Update.class);
    verify(mongo).findAndModify(
        query.capture(), update.capture(), any(FindAndModifyOptions.class),
        eq(Document.class), eq("sessions"));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("_kind", "browser_session", "payload.lastSeenOn");
    assertLiveSessionPredicate(query.getValue(), idleExpiresOn);
    assertThat(update.getValue().getUpdateObject().toString())
        .contains("payload.lastSeenOn", "payload.idleExpiresOn")
        .doesNotContain("tokenHash", "rotatedOn", "absoluteExpiresOn");
  }

  @Test
  void rotationUpdatesCredentialsAndActivityOnlyWhenTheObservedLiveSessionStillMatches() {
    var mongo = mock(MongoTemplate.class);
    var expected = BrowserSession.builder().id("session-1").build();
    var store = new MongoBrowserSessionActivityStore(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo));
    var expectedEnvelope =
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
            .envelope(mongo, expected);
    when(mongo.findAndModify(
        any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(Document.class), eq("sessions")))
        .thenReturn(expectedEnvelope);
    Instant previousTokenExpiresOn = NOW.plusSeconds(120);
    Instant idleExpiresOn = NOW.plusSeconds(600);

    Optional<BrowserSession> result = store.rotate(
        "session-1", "old-token-hash", OBSERVED, "next-token-hash", NOW,
        previousTokenExpiresOn, idleExpiresOn);

    assertThat(result).contains(expected);
    var query = ArgumentCaptor.forClass(Query.class);
    var update = ArgumentCaptor.forClass(Update.class);
    verify(mongo).findAndModify(
        query.capture(), update.capture(), any(FindAndModifyOptions.class),
        eq(Document.class), eq("sessions"));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("payload.tokenHash", "old-token-hash", "payload.rotatedOn");
    assertLiveSessionPredicate(query.getValue(), idleExpiresOn);
    assertThat(update.getValue().getUpdateObject().toString())
        .contains("payload.previousTokenHash", "old-token-hash",
            "payload.previousTokenExpiresOn", "payload.tokenHash", "next-token-hash",
            "payload.rotatedOn", "payload.lastSeenOn", "payload.idleExpiresOn")
        .doesNotContain("absoluteExpiresOn", "accountId");
  }

  private static void assertLiveSessionPredicate(Query query, Instant idleExpiresOn) {
    assertThat(query.getQueryObject().toString())
        .contains("_kind", "browser_session", "_id.legacyId", "session-1",
            "payload.idleExpiresOn", "payload.absoluteExpiresOn");
  }
}
