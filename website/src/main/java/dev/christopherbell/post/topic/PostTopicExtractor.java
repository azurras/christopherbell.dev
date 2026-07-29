package dev.christopherbell.post.topic;

import dev.christopherbell.post.model.PostTopic;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Extracts a small, safe set of hashtags from untrusted post text. */
@Component
public class PostTopicExtractor {
  private static final int MAX_TOPIC_CODE_POINTS = 40;
  private static final int MAX_TOPICS = 5;

  public List<PostTopic> extract(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }

    var normalizedText = Normalizer.normalize(text, Normalizer.Form.NFKC);
    var topicsByCanonicalName = new LinkedHashMap<String, PostTopic>();
    for (int offset = 0; offset < normalizedText.length() && topicsByCanonicalName.size() < MAX_TOPICS;) {
      int codePoint = normalizedText.codePointAt(offset);
      if (codePoint != '#') {
        offset += Character.charCount(codePoint);
        continue;
      }

      int topicStart = offset + Character.charCount(codePoint);
      int topicEnd = topicStart;
      int topicCodePoints = 0;
      while (topicEnd < normalizedText.length()) {
        int candidate = normalizedText.codePointAt(topicEnd);
        boolean allowed = topicCodePoints == 0 ? isTopicStart(candidate) : isTopicContinuation(candidate);
        if (!allowed) {
          break;
        }
        topicCodePoints++;
        topicEnd += Character.charCount(candidate);
      }

      if (topicCodePoints > 0 && topicCodePoints <= MAX_TOPIC_CODE_POINTS) {
        var display = normalizedText.substring(topicStart, topicEnd);
        var canonical = Normalizer.normalize(display.toLowerCase(Locale.ROOT), Normalizer.Form.NFKC);
        if (canonical.codePointCount(0, canonical.length()) <= MAX_TOPIC_CODE_POINTS) {
          topicsByCanonicalName.putIfAbsent(canonical, new PostTopic(canonical, display));
        }
      }
      offset = Math.max(topicEnd, topicStart);
    }

    return List.copyOf(new ArrayList<>(topicsByCanonicalName.values()));
  }

  private static boolean isTopicStart(int codePoint) {
    return Character.isLetterOrDigit(codePoint);
  }

  private static boolean isTopicContinuation(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isLetterOrDigit(codePoint)
        || codePoint == '_'
        || type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK;
  }
}
