package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantRepository;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class PublicSitemapServiceTest {
  @Mock private AccountRepository accounts;
  @Mock private PostRepository posts;
  @Mock private RestaurantRepository restaurants;
  private PublicSitemapService service;
  private final Instant now = Instant.parse("2026-07-29T12:00:00Z");

  @BeforeEach
  void setUp() {
    service = new PublicSitemapService(
        accounts, posts, restaurants, Clock.fixed(now, ZoneOffset.UTC), false, 10);
    when(accounts.countByStatus(AccountStatus.ACTIVE)).thenReturn(2L);
    org.mockito.Mockito.lenient().when(posts.count()).thenReturn(1L);
    when(restaurants.count()).thenReturn(1L);
  }

  @Test
  void invalidShardIsRejectedFromCountsWithoutScanningCollections() {
    assertThat(service.renderShard(Integer.MAX_VALUE)).isEmpty();

    verify(accounts, never()).findByStatus(any(), any(Pageable.class));
    verify(posts, never()).findAll(any(Pageable.class));
    verify(restaurants, never()).findAll(any(Pageable.class));
  }

  @Test
  void rootDocumentIndexesBoundedShardsWhenPublicUrlsExceedOneDocument() throws Exception {
    var root = service.renderRoot();

    assertThat(root)
        .contains("<sitemapindex")
        .contains("https://www.christopherbell.dev/sitemap-1.xml")
        .contains("https://www.christopherbell.dev/sitemap-2.xml");
    assertXml(root, "sitemapindex");
  }

  @Test
  void shardsContainStaticAndLiveDynamicCanonicalUrlsInDeterministicOrder() throws Exception {
    stubPublicContentPages();
    var first = service.renderShard(1);
    var second = service.renderShard(2);

    assertThat(first).isPresent();
    assertThat(second).isPresent();
    var combined = first.orElseThrow() + second.orElseThrow();
    assertThat(combined)
        .contains("https://www.christopherbell.dev/")
        .contains("https://www.christopherbell.dev/photos/usage")
        .contains("https://www.christopherbell.dev/thebell/tony")
        .contains("https://www.christopherbell.dev/wfl/top-liked")
        .doesNotContain("https://www.christopherbell.dev/wfl/top-rated")
        .contains("https://www.christopherbell.dev/u/active_user")
        .contains("https://www.christopherbell.dev/p/post%20id")
        .contains("https://www.christopherbell.dev/wfl/restaurants/rest%2Fone");
    assertThat(combined.indexOf("/u/active_user")).isLessThan(combined.indexOf("/u/zebra"));
    assertThat(service.renderShard(3)).isEmpty();
    assertXml(first.orElseThrow(), "urlset");
    assertXml(second.orElseThrow(), "urlset");
  }

  @Test
  void expirationEnabledIncludesOnlyPostsWhoseExpirationIsInTheFuture() {
    var expiringService = new PublicSitemapService(
        accounts, posts, restaurants, Clock.fixed(now, ZoneOffset.UTC), true, 50_000);
    when(posts.countByExpiresOnAfter(now)).thenReturn(1L);
    when(accounts.findByStatus(eq(AccountStatus.ACTIVE), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(
            Account.builder().id("account-1").username("active_user").build(),
            Account.builder().id("account-2").username("zebra").build())));
    when(posts.findByExpiresOnAfter(eq(now), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(
            Post.builder().id("future-post").expiresOn(now.plusSeconds(60)).build())));
    when(restaurants.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(Restaurant.builder().id("rest/one").build())));

    assertThat(expiringService.renderRoot())
        .contains("https://www.christopherbell.dev/p/future-post");
    verify(posts, never()).findAll(any(Pageable.class));
  }

  private void stubPublicContentPages() {
    when(accounts.findByStatus(eq(AccountStatus.ACTIVE), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(
            Account.builder().id("account-1").username("active_user").build(),
            Account.builder().id("account-2").username("zebra").build())));
    when(posts.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(Post.builder().id("post id").expiresOn(null).build())));
    when(restaurants.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(Restaurant.builder().id("rest/one").build())));
  }

  private static void assertXml(String xml, String rootName) throws Exception {
    var builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    var document = builder.parse(new java.io.ByteArrayInputStream(
        xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(document.getDocumentElement().getNodeName()).isEqualTo(rootName);
  }
}
