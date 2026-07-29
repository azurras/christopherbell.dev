package dev.christopherbell.post.model;

import java.text.Normalizer;
import java.util.Locale;

/** A normalized hashtag captured from post text for public discovery. */
public record PostTopic(String canonical, String display) {
  private static final int MAX_CODE_POINTS = 40;

  public PostTopic {
    requireValid(canonical, "canonical");
    requireValid(display, "display");
    if (!canonical.equals(canonical.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("Topic canonical form must be lowercase.");
    }
  }

  /** Normalizes and validates one topic route segment without accepting punctuation. */
  public static String canonicalizeRoute(String rawTopic) {
    if (rawTopic == null || rawTopic.isBlank()) {
      throw new IllegalArgumentException("Topic cannot be blank.");
    }
    var normalized = Normalizer.normalize(rawTopic.strip(), Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT);
    normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
    return new PostTopic(normalized, normalized).canonical();
  }

  private static void requireValid(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Topic " + field + " form cannot be blank.");
    }
    if (!Normalizer.isNormalized(value, Normalizer.Form.NFKC)) {
      throw new IllegalArgumentException("Topic " + field + " form must use NFKC normalization.");
    }
    if (value.codePointCount(0, value.length()) > MAX_CODE_POINTS) {
      throw new IllegalArgumentException("Topic " + field + " form is too long.");
    }
    int first = value.codePointAt(0);
    if (!Character.isLetterOrDigit(first)
        || value.codePoints().skip(1).anyMatch(codePoint -> !isContinuation(codePoint))) {
      throw new IllegalArgumentException("Topic " + field + " form contains invalid characters.");
    }
  }

  private static boolean isContinuation(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isLetterOrDigit(codePoint)
        || codePoint == '_'
        || type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK;
  }
}
