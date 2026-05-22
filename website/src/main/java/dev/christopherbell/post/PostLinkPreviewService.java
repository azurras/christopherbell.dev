package dev.christopherbell.post;

import dev.christopherbell.post.model.PostLinkPreview;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Extracts web URLs from post text and resolves stored preview metadata.
 */
@RequiredArgsConstructor
@Service
@Slf4j
public class PostLinkPreviewService {
  private static final Pattern WEB_URL = Pattern.compile("(?i)\\bhttps?://[^\\s<>()]+");
  private static final String TRAILING_PUNCTUATION = ".,!?;:";

  private final PostLinkPreviewClient postLinkPreviewClient;

  /**
   * Resolves each distinct HTTP or HTTPS URL in text in first-seen order.
   *
   * @param text user-authored post text
   * @return preview metadata for URLs that exposed usable metadata
   */
  public List<PostLinkPreview> resolveForText(String text) {
    var previews = new ArrayList<PostLinkPreview>();
    for (String url : extractUrls(text)) {
      try {
        postLinkPreviewClient.fetch(url).ifPresent(previews::add);
      } catch (RuntimeException exception) {
        log.debug("Link preview fetch failed for {}", url, exception);
      }
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
    return urls.stream().toList();
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
