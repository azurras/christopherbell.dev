package dev.christopherbell.post.preview;

import dev.christopherbell.post.model.PostLinkPreview;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Extracts web URLs from post text and resolves stored preview metadata.
 */
@Service
@Slf4j
public class PostLinkPreviewService {
  private static final Pattern WEB_URL = Pattern.compile("(?i)\\bhttps?://[^\\s<>()]+");
  private static final String TRAILING_PUNCTUATION = ".,!?;:";

  private final PostLinkPreviewClient postLinkPreviewClient;
  private final PostLinkPreviewCacheRepository cacheRepository;
  private final Clock clock;
  private final PostLinkPreviewProperties properties;

  public PostLinkPreviewService(PostLinkPreviewClient postLinkPreviewClient) {
    this(postLinkPreviewClient, null, Clock.systemUTC(), new PostLinkPreviewProperties());
  }

  @Autowired
  public PostLinkPreviewService(
      PostLinkPreviewClient postLinkPreviewClient,
      PostLinkPreviewCacheRepository cacheRepository,
      Clock clock,
      PostLinkPreviewProperties properties
  ) {
    this.postLinkPreviewClient = postLinkPreviewClient;
    this.cacheRepository = cacheRepository;
    this.clock = clock;
    this.properties = properties;
  }

  /**
   * Resolves each distinct HTTP or HTTPS URL in text in first-seen order.
   *
   * @param text user-authored post text
   * @return preview metadata for URLs that exposed usable metadata
   */
  public List<PostLinkPreview> resolveForText(String text) {
    var previews = new ArrayList<PostLinkPreview>();
    for (String url : extractUrls(text)) {
      resolve(url).ifPresent(previews::add);
    }
    return previews;
  }

  private List<String> extractUrls(String text) {
    var urls = new LinkedHashSet<String>();
    var matcher = WEB_URL.matcher(text == null ? "" : text);
    while (matcher.find()) {
      var url = trimTrailingPunctuation(matcher.group());
      if (!url.isBlank()) {
        urls.add(url);
      }
    }
    return urls.stream().limit(properties.getMaxUrlsPerPost()).toList();
  }

  private Optional<PostLinkPreview> resolve(String url) {
    var now = Instant.now(clock);
    var cached = findFresh(url, now);
    if (cached != null) {
      return "SUCCESS".equals(cached.getStatus())
          ? Optional.ofNullable(cached.getPreview())
          : Optional.empty();
    }

    try {
      var preview = postLinkPreviewClient.fetch(url);
      if (preview.isPresent()) {
        save(PostLinkPreviewCacheEntry.success(
            url, preview.orElseThrow(), now, now.plus(properties.getSuccessTtl())));
      } else {
        save(PostLinkPreviewCacheEntry.failure(
            url, "NO_METADATA", now, now.plus(properties.getFailureTtl())));
      }
      return preview;
    } catch (LinkPreviewFetchException failure) {
      save(PostLinkPreviewCacheEntry.failure(
          url,
          safeFailureCategory(failure.category()),
          now,
          now.plus(properties.getFailureTtl())));
      log.debug("Link preview fetch failed for {} with category {}.",
          url, safeFailureCategory(failure.category()));
      return Optional.empty();
    } catch (RuntimeException failure) {
      save(PostLinkPreviewCacheEntry.failure(
          url, "FETCH_FAILED", now, now.plus(properties.getFailureTtl())));
      log.debug("Link preview fetch failed for {}.", url, failure);
      return Optional.empty();
    }
  }

  private PostLinkPreviewCacheEntry findFresh(String url, Instant now) {
    if (cacheRepository == null) {
      return null;
    }
    try {
      return cacheRepository.findById(url).filter(entry -> entry.isFresh(now)).orElse(null);
    } catch (DataAccessException failure) {
      log.debug("Link preview cache read failed.", failure);
      return null;
    }
  }

  private void save(PostLinkPreviewCacheEntry entry) {
    if (cacheRepository == null) {
      return;
    }
    try {
      cacheRepository.save(entry);
    } catch (DataAccessException failure) {
      log.debug("Link preview cache write failed.", failure);
    }
  }

  private String safeFailureCategory(String category) {
    return switch (String.valueOf(category)) {
      case "DESTINATION_REJECTED", "INVALID_URL", "TIMEOUT", "REMOTE_IO", "INTERRUPTED",
          "TOO_MANY_REDIRECTS", "INVALID_REDIRECT", "HTTP_STATUS", "CONTENT_TYPE",
          "RESPONSE_TOO_LARGE", "NO_METADATA" -> category;
      default -> "FETCH_FAILED";
    };
  }

  private String trimTrailingPunctuation(String url) {
    var trimmed = url;
    while (!trimmed.isBlank()
        && TRAILING_PUNCTUATION.indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }
}
