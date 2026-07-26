package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.christopherbell.post.preview.JsoupPostLinkPreviewClient;
import dev.christopherbell.post.preview.PostLinkPreviewProperties;
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

  @Test
  void truncatesStoredMetadataAtConfiguredLengths() {
    var properties = new PostLinkPreviewProperties();
    properties.setMaxTitleLength(5);
    properties.setMaxDescriptionLength(7);
    properties.setMaxImageUrlLength(30);
    var document = Jsoup.parse("""
        <html><head>
          <meta property="og:title" content="123456789">
          <meta property="og:description" content="123456789">
          <meta property="og:image" content="/a-very-long-preview-image.jpg">
        </head></html>
        """, "https://example.com/page");

    var preview = new JsoupPostLinkPreviewClient(null, properties)
        .toPreview(URI.create("https://example.com/page"), document)
        .orElseThrow();

    assertEquals("12345", preview.title());
    assertEquals("1234567", preview.description());
    assertEquals(30, preview.imageUrl().length());
  }
}
