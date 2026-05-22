package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class JsoupPostLinkPreviewClientTest {
  @Test
  void buildsRichPreviewFromOpenGraphMetadata() {
    var document = Jsoup.parse("""
        <html>
          <head>
            <meta property="og:title" content="Lunch Picks">
            <meta property="og:description" content="Three places nearby">
            <meta property="og:image" content="/preview.jpg">
          </head>
        </html>
        """, "https://example.com/lunch");

    var preview = new JsoupPostLinkPreviewClient(250, 2048)
        .toPreview(URI.create("https://example.com/lunch"), document)
        .orElseThrow();

    assertEquals("example.com", preview.domain());
    assertEquals("Lunch Picks", preview.title());
    assertEquals("Three places nearby", preview.description());
    assertEquals("https://example.com/preview.jpg", preview.imageUrl());
  }
}
