package dev.christopherbell.federation.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class FederationOutboxQueryRepositoryTest {
  private static final Instant NOW = Instant.parse("2026-07-28T20:00:00Z");

  @Mock private MongoTemplate mongo;
  private StableCursorCodec cursors;
  private FederationOutboxQueryRepository repository;

  @BeforeEach
  void setUp() {
    cursors = new StableCursorCodec();
    repository = new FederationOutboxQueryRepository(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo),
        cursors);
  }

  @Test
  void pageLoadsOnlyExplicitlyFederationEligibleActiveOwnedPostsWithStableDescendingCursor()
      throws Exception {
    var boundary = post("post-2", NOW.minusSeconds(20));
    var extra = post("post-1", NOW.minusSeconds(30));
    var documents = List.of(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
            .envelope(mongo, boundary),
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
            .envelope(mongo, extra));
    when(mongo.find(any(Query.class), eq(Document.class), eq("content")))
        .thenReturn(documents);

    var page = repository.page(
        "account-123",
        Optional.of(new StableCursor(NOW.minusSeconds(10), "post-3")),
        1,
        NOW);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Document.class), eq("content"));
    var cursor = NOW.minusSeconds(10);
    assertThat(query.getValue().getQueryObject().toString())
        .contains("_kind=post", "payload.accountId=account-123",
            "payload.federationOutboundEligible=true", "payload.expiresOn",
            "payload.createdOn", "_id.legacyId", "post-3");
    assertThat(query.getValue().getSortObject())
        .isEqualTo(new Document("payload.createdOn", -1).append("_id.legacyId", -1));
    assertThat(query.getValue().getLimit()).isEqualTo(2);
    assertThat(page.items()).containsExactly(new FederationOutboxEntry(
        boundary.getId(),
        boundary.getText(),
        boundary.getParentId(),
        boundary.getCreatedOn(),
        boundary.getLastUpdatedOn()));
    assertThat(cursors.decode(page.nextCursor()))
        .contains(new StableCursor(boundary.getCreatedOn(), boundary.getId()));
  }

  @Test
  void countUsesMongoTrueEqualitySoFalseNullAndMissingHistoricalValuesStayExcluded() {
    when(mongo.count(any(Query.class), eq(Document.class), eq("content"))).thenReturn(7L);

    long count = repository.count("account-123", NOW);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).count(query.capture(), eq(Document.class), eq("content"));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("_kind=post", "payload.accountId=account-123",
            "payload.federationOutboundEligible=true", "payload.expiresOn",
            "payload.createdOn");
    assertThat(count).isEqualTo(7L);
  }

  @Test
  void requestedPageSizeIsClampedToTwenty() {
    when(mongo.find(any(Query.class), eq(Document.class), eq("content"))).thenReturn(List.of());

    repository.page("account-123", Optional.empty(), 500, NOW);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Document.class), eq("content"));
    assertThat(query.getValue().getLimit()).isEqualTo(21);
  }

  private static Post post(String id, Instant createdOn) {
    return Post.builder()
        .id(id)
        .accountId("account-123")
        .text("hello")
        .createdOn(createdOn)
        .expiresOn(NOW.plusSeconds(60))
        .build();
  }

  private static Document activeOwned(String accountId) {
    // Mongo equality with true excludes false, null, and documents missing the field.
    return new Document("accountId", accountId)
        .append("federationOutboundEligible", true)
        .append("expiresOn", new Document("$gt", NOW))
        .append("createdOn", new Document("$ne", null));
  }
}
