package dev.christopherbell.federation.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.pagination.StableCursor;
import dev.christopherbell.pagination.StableCursorCodec;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    repository = new FederationOutboxQueryRepository(mongo, cursors);
  }

  @Test
  void pageLoadsOnlyActiveOwnedPostsWithStableDescendingCursor() throws Exception {
    var boundary = post("post-2", NOW.minusSeconds(20));
    var extra = post("post-1", NOW.minusSeconds(30));
    when(mongo.find(any(Query.class), eq(Post.class))).thenReturn(List.of(boundary, extra));

    var page = repository.page(
        "account-123",
        Optional.of(new StableCursor(NOW.minusSeconds(10), "post-3")),
        1,
        NOW);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Post.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("accountId", "account-123", "expiresOn", "$gt", "createdOn", "$lt", "_id");
    assertThat(query.getValue().getSortObject().toString())
        .contains("createdOn=-1", "_id=-1");
    assertThat(query.getValue().getLimit()).isEqualTo(2);
    assertThat(page.items()).containsExactly(boundary);
    assertThat(cursors.decode(page.nextCursor()))
        .contains(new StableCursor(boundary.getCreatedOn(), boundary.getId()));
  }

  @Test
  void requestedPageSizeIsClampedToTwenty() {
    when(mongo.find(any(Query.class), eq(Post.class))).thenReturn(List.of());

    repository.page("account-123", Optional.empty(), 500, NOW);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Post.class));
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
}
