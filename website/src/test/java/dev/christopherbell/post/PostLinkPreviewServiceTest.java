package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.christopherbell.post.model.PostLinkPreview;
import dev.christopherbell.post.preview.PostLinkPreviewClient;
import dev.christopherbell.post.preview.PostLinkPreviewService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostLinkPreviewServiceTest {
  @Mock private PostLinkPreviewClient postLinkPreviewClient;

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
}
