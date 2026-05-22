package dev.christopherbell.post;

import dev.christopherbell.post.model.PostLinkPreview;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves post link preview metadata from public HTML pages with JSoup.
 */
@Component
@Slf4j
public class JsoupPostLinkPreviewClient implements PostLinkPreviewClient {
  private final int timeoutMillis;
  private final int maxBodyBytes;

  public JsoupPostLinkPreviewClient(
      @Value("${posts.link-previews.timeout-millis:2500}") int timeoutMillis,
      @Value("${posts.link-previews.max-body-bytes:524288}") int maxBodyBytes) {
    this.timeoutMillis = timeoutMillis;
    this.maxBodyBytes = maxBodyBytes;
  }

  @Override
  public Optional<PostLinkPreview> fetch(String url) {
    try {
      var uri = URI.create(url);
      if (!isPublicWebUri(uri)) {
        return Optional.empty();
      }

      var response = Jsoup.connect(uri.toString())
          .followRedirects(false)
          .ignoreHttpErrors(true)
          .maxBodySize(maxBodyBytes)
          .timeout(timeoutMillis)
          .userAgent("christopherbell.dev link preview fetcher")
          .execute();
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return Optional.empty();
      }

      var contentType = String.valueOf(response.contentType()).toLowerCase(Locale.ROOT);
      if (!contentType.contains("html")) {
        return Optional.empty();
      }

      return toPreview(uri, response.parse());
    } catch (Exception exception) {
      log.debug("Unable to resolve link preview for {}", url, exception);
      return Optional.empty();
    }
  }

  Optional<PostLinkPreview> toPreview(URI uri, Document document) {
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
    if (imageUrl != null && !imageUrl.isBlank()) {
      imageUrl = document.baseUri().isBlank()
          ? uri.resolve(imageUrl).toString()
          : document.selectFirst("meta[property=og:image], meta[name=twitter:image]").absUrl("content");
      if (imageUrl == null || imageUrl.isBlank()) {
        imageUrl = null;
      }
    }

    var domain = uri.getHost();
    if ((title == null || title.isBlank()) && (domain == null || domain.isBlank())) {
      return Optional.empty();
    }

    return Optional.of(PostLinkPreview.builder()
        .url(uri.toString())
        .domain(domain)
        .title(blankToNull(title))
        .description(blankToNull(description))
        .imageUrl(blankToNull(imageUrl))
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

  private boolean isPublicWebUri(URI uri) throws UnknownHostException {
    if (uri == null || uri.getHost() == null) {
      return false;
    }
    var scheme = uri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
      return false;
    }
    for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
      if (address.isAnyLocalAddress()
          || address.isLoopbackAddress()
          || address.isLinkLocalAddress()
          || address.isSiteLocalAddress()
          || address.isMulticastAddress()) {
        return false;
      }
    }
    return true;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
