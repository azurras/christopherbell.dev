package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import dev.christopherbell.post.model.PostLinkPreview;
import dev.christopherbell.post.preview.PostLinkPreviewClient;
import dev.christopherbell.post.preview.PostLinkPreviewService;
import dev.christopherbell.post.preview.PostLinkPreviewCacheEntry;
import dev.christopherbell.post.preview.PostLinkPreviewCacheRepository;
import dev.christopherbell.post.preview.PostLinkPreviewProperties;
import dev.christopherbell.post.preview.LinkPreviewFetchException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostLinkPreviewServiceTest {
  @Mock private PostLinkPreviewClient postLinkPreviewClient;
  @Mock private PostLinkPreviewCacheRepository cacheRepository;

  @Test
  void resolvesEveryDistinctWebLinkInTextOrder() {
    var first = PostLinkPreview.builder()
        .url("https://example.com/one")
        .domain("example.com")
        .title("One")
        .build();
    var second = PostLinkPreview.builder()
        .url("http://news.example/two")
        .domain("news.example")
        .title("Two")
        .build();
    when(postLinkPreviewClient.fetch(eq("https://example.com/one"))).thenReturn(Optional.of(first));
    when(postLinkPreviewClient.fetch(eq("http://news.example/two"))).thenReturn(Optional.of(second));

    var previews = new PostLinkPreviewService(postLinkPreviewClient).resolveForText(
        "First https://example.com/one, again https://example.com/one and http://news.example/two.");

    assertEquals(List.of(first, second), previews);
  }

  @Test
  void freshFailureCacheAvoidsAnotherOutboundFetch() {
    var now = Instant.parse("2026-07-26T12:00:00Z");
    var url = "https://failure.example/page";
    when(cacheRepository.findById(url)).thenReturn(Optional.of(
        PostLinkPreviewCacheEntry.failure(
            url, "TIMEOUT", now.minusSeconds(5), now.plus(Duration.ofMinutes(10)))));

    var previews = service(now).resolveForText("Read " + url);

    assertEquals(List.of(), previews);
    verifyNoInteractions(postLinkPreviewClient);
  }

  @Test
  void fetchFailureIsCachedAndDoesNotFailPostResolution() {
    var now = Instant.parse("2026-07-26T12:00:00Z");
    var url = "https://failure.example/page";
    when(cacheRepository.findById(url)).thenReturn(Optional.empty());
    when(postLinkPreviewClient.fetch(url)).thenThrow(new LinkPreviewFetchException("TIMEOUT"));

    var previews = service(now).resolveForText("Read " + url);

    assertEquals(List.of(), previews);
    verify(cacheRepository).save(any(PostLinkPreviewCacheEntry.class));
  }

  @Test
  void resolvesOnlyTheConfiguredMaximumNumberOfUrls() {
    var now = Instant.parse("2026-07-26T12:00:00Z");
    var properties = properties();
    properties.setMaxUrlsPerPost(1);
    when(cacheRepository.findById("https://one.example/")).thenReturn(Optional.empty());
    when(postLinkPreviewClient.fetch("https://one.example/")).thenReturn(Optional.empty());

    new PostLinkPreviewService(
        postLinkPreviewClient,
        cacheRepository,
        Clock.fixed(now, ZoneOffset.UTC),
        properties).resolveForText("https://one.example/ https://two.example/");

    verify(postLinkPreviewClient).fetch("https://one.example/");
    verify(postLinkPreviewClient, org.mockito.Mockito.never()).fetch("https://two.example/");
  }

  private PostLinkPreviewService service(Instant now) {
    return new PostLinkPreviewService(
        postLinkPreviewClient,
        cacheRepository,
        Clock.fixed(now, ZoneOffset.UTC),
        properties());
  }

  private PostLinkPreviewProperties properties() {
    var properties = new PostLinkPreviewProperties();
    properties.setSuccessTtl(Duration.ofDays(7));
    properties.setFailureTtl(Duration.ofMinutes(15));
    properties.setMaxUrlsPerPost(3);
    return properties;
  }
}
