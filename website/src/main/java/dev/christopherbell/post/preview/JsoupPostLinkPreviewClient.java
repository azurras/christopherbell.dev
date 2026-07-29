package dev.christopherbell.post.preview;

import dev.christopherbell.post.model.PostLinkPreview;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves post link preview metadata from public HTML pages with JSoup.
 */
@Component
public class JsoupPostLinkPreviewClient implements PostLinkPreviewClient {
  private final BoundedLinkPreviewHttpClient transport;
  private final PostLinkPreviewProperties properties;

  @Autowired
  public JsoupPostLinkPreviewClient(
      BoundedLinkPreviewHttpClient transport,
      PostLinkPreviewProperties properties) {
    this.transport = transport;
    this.properties = properties;
  }

  /** Test-only parser constructor retained for focused metadata tests. */
  public JsoupPostLinkPreviewClient(int timeoutMillis, int maxBodyBytes) {
    this.transport = null;
    this.properties = new PostLinkPreviewProperties();
    this.properties.setRequestTimeout(java.time.Duration.ofMillis(timeoutMillis));
    this.properties.setMaxResponseBytes(maxBodyBytes);
  }

  @Override
  public Optional<PostLinkPreview> fetch(String url) {
    try {
      var uri = URI.create(url);
      if (transport == null) {
        throw new IllegalStateException("Link preview transport is unavailable.");
      }
      var fetched = transport.fetch(uri);
      var document = Jsoup.parse(
          new String(fetched.body(), StandardCharsets.UTF_8), fetched.finalUri().toString());
      return toPreview(fetched.finalUri(), document);
    } catch (IllegalArgumentException failure) {
      throw new LinkPreviewFetchException("INVALID_URL", failure);
    }
  }

  public Optional<PostLinkPreview> toPreview(URI uri, Document document) {
    var title = firstText(document,
        "meta[property=og:title]",
        "meta[name=twitter:title]");
    if (title == null || title.isBlank()) {
      title = document.title();
    }
    var description = firstText(document,
        "meta[property=og:description]",
        "meta[name=twitter:description]",
        "meta[name=description]");
    var imageUrl = firstText(document,
        "meta[property=og:image]",
        "meta[name=twitter:image]");
    imageUrl = boundedAbsoluteHttpUrl(imageUrl, properties.getMaxImageUrlLength());

    var domain = uri.getHost();
    if ((title == null || title.isBlank()) && (domain == null || domain.isBlank())) {
      return Optional.empty();
    }

    return Optional.of(PostLinkPreview.builder()
        .url(uri.toString())
        .domain(domain)
        .title(bounded(title, properties.getMaxTitleLength()))
        .description(bounded(description, properties.getMaxDescriptionLength()))
        .imageUrl(imageUrl)
        .build());
  }

  private String firstText(Document document, String... selectors) {
    for (String selector : selectors) {
      var element = document.selectFirst(selector);
      if (element == null) {
        continue;
      }
      var value = element.attr("content");
      if (value != null && !value.isBlank()) {
        return value.strip();
      }
    }
    return null;
  }

  private String bounded(String value, int maxLength) {
    if (value == null || value.isBlank()) {
      return null;
    }
    var stripped = value.strip();
    return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength);
  }

  private String boundedAbsoluteHttpUrl(String value, int maxLength) {
    var bounded = bounded(value, maxLength);
    if (bounded == null) {
      return null;
    }
    try {
      var parsed = URI.create(bounded);
      var scheme = parsed.getScheme();
      if (!parsed.isAbsolute()
          || scheme == null
          || !("http".equals(scheme.toLowerCase(Locale.ROOT))
              || "https".equals(scheme.toLowerCase(Locale.ROOT)))
          || parsed.getHost() == null
          || parsed.getHost().isBlank()) {
        return null;
      }
      return bounded;
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
