package dev.christopherbell.post.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.pagination.StableCursorCodec;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.feed.PostEngagementQueryRepository;
import dev.christopherbell.post.feed.PostFeedItemAssembler;
import dev.christopherbell.post.like.PostLikeStore;
import dev.christopherbell.post.model.Post;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoidDiscoveryServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-29T04:00:00Z");

  @Mock private VoidDiscoveryQueryRepository queries;
  @Mock private AccountRepository accounts;
  @Mock private PostEngagementQueryRepository engagement;
  @Mock private PostLikeStore likes;
  @Mock private PermissionService permissions;
  private VoidDiscoveryService service;

  @BeforeEach
  void setUp() {
    service = new VoidDiscoveryService(
        queries,
        accounts,
        new StableCursorCodec(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        permissions,
        new PostFeedItemAssembler(engagement, likes));
  }

  @Test
  void mapsBoundedPostPagesWithoutAuthentication() throws Exception {
    var post = Post.builder().id("p1").accountId("a1").text("hello").build();
    when(queries.newArrivals(Optional.empty(), 12, NOW))
        .thenReturn(new VoidDiscoveryPage<>(List.of(post), "next"));
    when(accounts.findAllById(eq(List.of("a1"))))
        .thenReturn(List.of(Account.builder().id("a1").username("artist").build()));
    when(engagement.replyCounts(List.of("p1"))).thenReturn(Map.of("p1", 2));
    when(likes.counts(List.of("p1"))).thenReturn(Map.of());
    when(likes.likedPostIds(null, List.of("p1"))).thenReturn(java.util.Set.of());

    var page = service.newArrivals("", 12);

    assertThat(page.items()).singleElement().satisfies(item -> {
      assertThat(item.id()).isEqualTo("p1");
      assertThat(item.username()).isEqualTo("artist");
      assertThat(item.replyCount()).isEqualTo(2);
      assertThat(item.liked()).isFalse();
    });
    assertThat(page.nextCursor()).isEqualTo("next");
  }

  @Test
  void rejectsMalformedCursorBeforeQueryingMongo() {
    assertThatThrownBy(() -> service.newArrivals("not-a-cursor", 12))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("Invalid pagination cursor.");
  }

  @Test
  void canonicalizesSafeTopicsAndRejectsMalformedTopics() throws Exception {
    when(queries.topic(eq("music"), any(), eq(12), eq(NOW)))
        .thenReturn(new VoidDiscoveryPage<>(List.of(), null));

    service.topic("ＭＵＳＩＣ", "", 12);

    assertThatThrownBy(() -> service.topic("bad.topic", "", 12))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("Invalid topic.");
  }
}
