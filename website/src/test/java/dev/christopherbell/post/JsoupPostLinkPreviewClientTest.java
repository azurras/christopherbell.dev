package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.christopherbell.post.preview.JsoupPostLinkPreviewClient;
import dev.christopherbell.post.preview.PostLinkPreviewProperties;
import java.net.URI;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsoupPostLinkPreviewClientTest {
  @Test
  void buildsRichPreviewFromOpenGraphMetadata() {
    var document = Jsoup.parse("""
        <html>
          <head>
            <meta property="og:title" content="Lunch Picks">
            <meta property="og:description" content="Three places nearby">
            <meta property="og:image" content="https://cdn.example.com/preview.jpg">
          </head>
        </html>
        """, "https://example.com/lunch");

    var preview = new JsoupPostLinkPreviewClient(250, 2048)
        .toPreview(URI.create("https://example.com/lunch"), document)
        .orElseThrow();

    assertEquals("example.com", preview.domain());
    assertEquals("Lunch Picks", preview.title());
    assertEquals("Three places nearby", preview.description());
    assertEquals("https://cdn.example.com/preview.jpg", preview.imageUrl());
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
          <meta property="og:image" content="https://example.com/a-very-long-preview-image.jpg">
        </head></html>
        """, "https://example.com/page");

    var preview = new JsoupPostLinkPreviewClient(null, properties)
        .toPreview(URI.create("https://example.com/page"), document)
        .orElseThrow();

    assertEquals("12345", preview.title());
    assertEquals("1234567", preview.description());
    assertEquals(30, preview.imageUrl().length());
  }

  @Test
  void rejectsMalformedImageMetadataEvenWhenTheMalformedSuffixExceedsTheStorageLimit() {
    var validPrefix = "https://example.com/image.jpg";
    var properties = new PostLinkPreviewProperties();
    properties.setMaxImageUrlLength(validPrefix.length());
    var document = Jsoup.parse("""
        <html><head>
          <title>Safe title</title>
          <meta property="og:image" content="%s[malformed">
        </head></html>
        """.formatted(validPrefix), "https://example.com/page");

    var preview = new JsoupPostLinkPreviewClient(null, properties)
        .toPreview(URI.create("https://example.com/page"), document)
        .orElseThrow();

    assertNull(preview.imageUrl());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "javascript:alert(1)",
      "data:image/png;base64,AAAA",
      "/relative-preview.jpg",
      "//cdn.example.com/protocol-relative.jpg",
      "http://[malformed"
  })
  void discardsPreviewImagesThatAreNotAbsoluteHttpUrls(String imageValue) {
    var document = Jsoup.parse("""
        <html><head>
          <title>Safe title</title>
          <meta property="og:image" content="%s">
        </head></html>
        """.formatted(imageValue), "https://example.com/page");

    var preview = new JsoupPostLinkPreviewClient(250, 2048)
        .toPreview(URI.create("https://example.com/page"), document)
        .orElseThrow();

    assertNull(preview.imageUrl());
  }
}
